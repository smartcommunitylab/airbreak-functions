from datetime import datetime

import io

import os
import boto3
import botocore
from botocore.client import Config

S3_ENDPOINT = os.environ['S3_ENDPOINT']
S3_ACCESS_KEY = os.environ['S3_ACCESS_KEY']
S3_SECRET_KEY = os.environ['S3_SECRET_KEY']
S3_BUCKET = os.environ['S3_BUCKET']

DAYS = ['1-LUN', '2-MAR', '3-MER', '4-GIO', '5-VEN', '6-SAB', '7-DOM']

def date_selection(str):
    datetime_object = datetime.strptime(str, '%Y-%m-%d')
    return DAYS[datetime_object.weekday()]

def handler(context, event):
    dt = datetime.now() if event.path == '' or event.path == '/' else datetime.strptime(event.path, '/%Y-%m-%d')

    # init client
    s3 = boto3.client('s3',
                      endpoint_url=S3_ENDPOINT,
                      aws_access_key_id=S3_ACCESS_KEY,
                      aws_secret_access_key=S3_SECRET_KEY,
                      config=Config(signature_version='s3v4'),
                      region_name='us-east-1')

    DATE_SELECTION = date_selection(dt.strftime('%Y-%m-%d'))
    obj = None
    try:
        obj = s3.get_object(Bucket=S3_BUCKET, Key='data/{}.geojson'.format(dt.strftime('%Y-%m-%d')))
    except:
        try:
            obj = s3.get_object(Bucket=S3_BUCKET, Key='data/{}.geojson'.format(DATE_SELECTION))
        except:
            return {}
    
    return obj['Body'].read()
