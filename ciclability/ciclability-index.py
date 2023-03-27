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

DAYS = ['1-LUN', '2-MAR', '3-MER', '4-GIO', '5-VEN', '6-SAB', '7-DOM']


def date_selection(str):
    datetime_object = datetime.strptime(str, '%Y-%m-%d')
    return DAYS[datetime_object.weekday()]

def handler(context, event):
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

    # define server connection
    wfs = WebFeatureService(url='https://sit.comune.fe.it/geoserversit/wfs', username=GIS_USERNAME, 
                         password=GIS_PASSWORD, version='2.0.0')
    BASE_PATH = '/data/'
    DATE_SELECTION = date_selection(datetime.now().strftime('%Y-%m-%d'))

    # retrieve data from the server and save them into BASE_PATH
    floodData = wfs.getfeature(typename='Ferrara_AirBreak_int_X_Geoserver_SIT:Conteggi_Bici_per_Settimana', 
                                bbox=(11.2742899,44.5723508,12.3475256,44.9849783), 
                                outputFormat='SHAPE-ZIP')

    out = open(BASE_PATH+'DATA.zip', 'wb')
    out.write(floodData.read())
    out.close()

    # unzip the files downloaded and read the shapefile
    with zipfile.ZipFile(BASE_PATH+'DATA.zip',"r") as zip_ref:
        zip_ref.extractall(BASE_PATH)
        
    pg_bike = gpd.read_file(BASE_PATH + 'Conteggi_Bici_per_Settimana.shp')
    pg_bike = pg_bike.to_crs("EPSG:4326")

    # select only the necessary data 
    pg_bike_subset = pg_bike[pg_bike['periodo'] == DATE_SELECTION]

    ######
    def flip(x, y):
        """Flips the x and y coordinate values"""
        return y, x

    # flip coordinates since they are swapped in the data
    #pg_bike_subset.geometry = pg_bike_subset.geometry.apply(lambda x: transform(flip, x))
    ######
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
    # context.logger.info('fetch data for city '+lst.head())
    # lst.set_geometry('geometry')

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

    # now we can compute the priorities
    mapping['priority'] = mapping['class'] * mapping['num_totale']
    ########
    mapping = mapping.drop(columns=['periodo','num_totale','geometry','num_medio_','nome_segme','id','class','dist'])
    mapping.rename(columns={'geometry_line':'geometry'}, inplace=True)
    # flip coordinates since they are swapped in the data
    #mapping.geometry = mapping.geometry.apply(lambda x: transform(flip, x))
    ########
    mapping
    # finally, we save the geojson file
    bytes = io.BytesIO()
    mapping.to_file(bytes, driver='GeoJSON')
    bytes.seek(0)
    s3.upload_fileobj(bytes, S3_BUCKET, 'data/'+DATE_SELECTION+'.geojson')
    
    bytes = io.BytesIO()
    mapping.to_file(bytes, driver='GeoJSON')
    bytes.seek(0)
    s3.upload_fileobj(bytes, S3_BUCKET, 'data/'+datetime.now().strftime('%Y-%m-%d')+'.geojson')

    return "done"
