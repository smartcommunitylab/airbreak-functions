# import relevant libraries
import geopandas as gpd

from datetime import datetime

import numpy as np
import pandas as pd
import io

import os
import boto3
import botocore
from botocore.client import Config

from scipy.spatial import cKDTree
from shapely.geometry import Point

######
from shapely.ops import transform
######

import zipfile

from owslib.wfs import WebFeatureService

GIS_USERNAME = os.environ['GIS_USERNAME']
GIS_PASSWORD = os.environ['GIS_PASSWORD']
S3_ENDPOINT = os.environ['S3_ENDPOINT']
S3_ACCESS_KEY = os.environ['S3_ACCESS_KEY']
S3_SECRET_KEY = os.environ['S3_SECRET_KEY']
S3_BUCKET = os.environ['S3_BUCKET']



def process(DATE_SELECTION):
    # define server connection
    wfs = WebFeatureService(url='https://sit.comune.fe.it/geoserversit/wfs', username=GIS_USERNAME, 
                         password=GIS_PASSWORD, version='2.0.0')
                         
    BASE_PATH = '/data/'
    BASE_SIM_PATH = '/data/'
    
    # init client
    s3 = boto3.client('s3',
                      endpoint_url=S3_ENDPOINT,
                      aws_access_key_id=S3_ACCESS_KEY,
                      aws_secret_access_key=S3_SECRET_KEY,
                      config=Config(signature_version='s3v4'),
                      region_name='us-east-1')

    # reading LST geojsons (static files, computed one time) in a single 
    # GeoDataFrame
    dfs = []
    for i in range(5):
        obj = s3.get_object(Bucket=S3_BUCKET, Key='data/stressmap/lts_{}.json'.format(i))
        dataio = io.BytesIO(obj['Body'].read())
        t = gpd.read_file(dataio)
        t.crs = "EPSG:4326"
        t['class'] = i
        dfs.append(t)
    lst = pd.concat(dfs)
    del dfs

    # compute the centroid of the geometry
    lst['centroid'] = lst['geometry'].centroid

    # retrieve data from the server and save them into BASE_PATH
    floodData = wfs.getfeature(typename='Ferrara_AirBreak_int_X_Geoserver_SIT:Conteggi_Bici_per_Mese', 
                                bbox=(11.2742899,44.5723508,12.3475256,44.9849783), 
                                outputFormat='SHAPE-ZIP')

    out = open(BASE_PATH+'DATA.zip', 'wb')
    out.write(floodData.read())
    out.close()

    # unzip the files downloaded and read the shapefile
    with zipfile.ZipFile(BASE_PATH+'DATA.zip',"r") as zip_ref:
        zip_ref.extractall(BASE_PATH)
        
    pg_bike = gpd.read_file(BASE_PATH + 'Conteggi_Bici_per_Mese.shp')
    pg_bike = pg_bike.to_crs("EPSG:4326")

    # select only the necessary data 
    pg_bike_subset = pg_bike[pg_bike['periodo'] == DATE_SELECTION]

    ######
    def flip(x, y):
        """Flips the x and y coordinate values"""
        return y, x

    # flip coordinates since they are swapped in the data
    pg_bike_subset.geometry = pg_bike_subset.geometry.apply(lambda x: transform(flip, x))
    ######

    # mapping all the possible traffic data (we have more entries in bike data than traffic data) 
    s3.download_file(S3_BUCKET, 'data/simulazione_traffico.zip', BASE_SIM_PATH + 'simulazione_traffico.zip')
    # unzip the files downloaded and read the shapefile
    with zipfile.ZipFile(BASE_SIM_PATH+'simulazione_traffico.zip',"r") as zip_ref:
        zip_ref.extractall(BASE_SIM_PATH)
    traffic_data = gpd.read_file(BASE_SIM_PATH + 'simulazione_traffico/Stima_flussi_di_traffico.shp')
    traffic_data = traffic_data[['TOTALE_VEI', 'geometry']]
    traffic_data = traffic_data.to_crs('epsg:4326')
    pg_bike_subset = gpd.sjoin(pg_bike_subset, traffic_data, how='left', op='intersects')
    pg_bike_subset.drop(columns='index_right', inplace=True)
    pg_bike_subset.TOTALE_VEI.fillna(0,inplace=True)

    # computing the centroids is necessary to efficently map the 
    # the bike network with the LTS network. 
    # compute the centroid of the bike geometries
    pg_bike_subset['centroid'] = pg_bike_subset['geometry'].centroid
    pg_bike_subset.drop(columns=['geometry'], inplace=True)
    pg_bike_subset.rename(columns={'centroid':'geometry'}, inplace=True)
    
    # compute the centroid of the LTS geometries
    ########
    # lst.drop(columns=['geometry'], inplace=True)
    lst.rename(columns={'geometry':'geometry_line'}, inplace=True)
    ########
    lst.rename(columns={'centroid':'geometry'}, inplace=True)

    # a function to compute hte CKD nearests points to map the two networks 
    def ckdnearest(gdA, gdB):
        nA = np.array(list(gdA.geometry.apply(lambda x: (x.x, x.y))))
        nB = np.array(list(gdB.geometry.apply(lambda x: (x.x, x.y))))
        btree = cKDTree(nB)
        dist, idx = btree.query(nA, k=1)
        gdB_nearest = gdB.iloc[idx].drop(columns="geometry").reset_index(drop=True)
        gdf = pd.concat(
            [
                gdA.reset_index(drop=True),
                gdB_nearest,
                pd.Series(dist, name='dist')
            ], 
            axis=1)

        return gdf

    # map the bike network and the LTS network 
    mapping = ckdnearest(pg_bike_subset, lst)

    # integration and normalizing traffic (4.4.2-p20)
    mapping['ILTS'] = mapping['TOTALE_VEI'] * mapping['class']

    max_traffic = mapping['ILTS'].max()
    min_traffic = mapping['ILTS'].min()
    mapping['ILTS'] = (mapping['ILTS']-min_traffic)/(max_traffic-min_traffic)

    # computing the final policy score 
    mapping['final_score'] = np.log(mapping['num_totale']) * mapping['ILTS']

    mapping = mapping[['id_segment', 'geometry_line', 'final_score']]
    mapping.rename(columns={'geometry_line':'geometry', 'final_score': 'priority'}, inplace=True)

    mapping = mapping.sort_values('final_score', ascending=False)
    mapping = mapping.drop_duplicates()

    mapping = gpd.GeoDataFrame(mapping)
    
    # finally, we save the geojson file
    bytes = io.BytesIO()
    mapping.to_file(bytes, driver='GeoJSON')
    bytes.seek(0)
    s3.upload_fileobj(bytes, S3_BUCKET, 'data/'+DATE_SELECTION+'.geojson')
 

def handler(context, event):
    process(datetime.now().strftime('%Y-%m'))
    process('COMPLESSIVO')
    
    return "done"
