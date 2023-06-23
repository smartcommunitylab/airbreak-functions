from datetime import datetime

import io
import nuclio_sdk

import os
import boto3
import botocore
from botocore.client import Config

S3_ENDPOINT = os.environ['S3_ENDPOINT']
S3_ACCESS_KEY = os.environ['S3_ACCESS_KEY']
S3_SECRET_KEY = os.environ['S3_SECRET_KEY']
S3_BUCKET = os.environ['S3_BUCKET']

def handler(context, event):
    dt = 'COMPLESSIVO' if event.path == '' or event.path == '/' else datetime.strptime(event.path, '/%Y-%m').strftime('%Y-%m')

    # init client
    s3 = boto3.client('s3',
                      endpoint_url=S3_ENDPOINT,
                      aws_access_key_id=S3_ACCESS_KEY,
                      aws_secret_access_key=S3_SECRET_KEY,
                      config=Config(signature_version='s3v4'),
                      region_name='us-east-1')

    DATE_SELECTION = dt
    obj = None
    try:
        obj = s3.get_object(Bucket=S3_BUCKET, Key='data/{}.geojson'.format(DATE_SELECTION))
    except:
        try:
            obj = s3.get_object(Bucket=S3_BUCKET, Key='data/{}.geojson'.format('COMPLESSIVO'))
        except:
            return {}
    
    #obj['Body'].read()
    response = nuclio_sdk.Response()
    response.status_code = 200
    response.body = obj['Body'].read()
    response.content_type='application/json'
    return response
