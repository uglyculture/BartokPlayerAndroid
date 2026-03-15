import datetime
import requests
import hashlib
import pytz

import asyncio

import argparse
import contextlib
import datetime
import os
import six
import sys
import time
import unicodedata

import dropbox

import bytefifo

from time import sleep




def upload_to_dropbox(dbx, fullname, remote_path, overwrite=False):
    """Upload a file.
    Return the request response, or None in case of error.
    """
    path = remote_path
    # path = '/%s/%s/%s' % (folder, subfolder.replace(os.path.sep, '/'), name)
    while '//' in path:
        path = path.replace('//', '/')
    mode = (dropbox.files.WriteMode.overwrite
            if overwrite
            else dropbox.files.WriteMode.add)
    mtime = os.path.getmtime(fullname)
    with open(fullname, 'rb') as f:
        data = f.read()
    # with stopwatch('upload %d bytes' % len(data)):
    try:
        res = dbx.files_upload(
            data, path)#, mode,
            # client_modified=datetime.datetime(*time.gmtime(mtime)[:6]),
            # mute=True)
    except dropbox.exceptions.ApiError as err:
        print('*** API error', err)
        return None
    print('uploaded as', res.name.encode('utf8'))
    os.remove(fullname)
    return res



def send_new_files(local_folder, remote_folder):
    files = os.listdir(local_folder)

    def get_age_hours(file):
        file_path = os.path.join(local_folder, file)
        file_date = datetime.datetime.fromtimestamp(os.stat(file_path).st_mtime)
        file_age = datetime.datetime.now()-file_date
        return file_age.days*24 + file_age.seconds/60/60

    new_files = [file for file in files if get_age_hours(file) > 1.1]

    print(new_files)

    for file in new_files:
        local_file = os.path.join(local_folder, file)
        print(f'uploading {file}')
        upload_to_dropbox(
            dbx,
            local_file,
            os.path.join(remote_folder, file))
        os.remove(local_file)




budapest_timezone = pytz.timezone('Europe/Budapest')


def find_seqs(buffer, templates, start = 0):
    results = [buffer.find(template, start) for template in templates]
    real_results = [p for p in results if p>-1]
    if len(real_results) > 0:
        return min(real_results)
    else:
        return -1

headers = dict()

def download_stream(url, local_file_path_template):
    try:
        fifo = bytefifo.BytesFIFO(5*1024)
        header_start_seqs = [
            bytes.fromhex('fffb')]

        now = datetime.datetime.now(budapest_timezone)
        prev_time = now
        local_file_path = now.strftime(local_file_path_template)
        with open(local_file_path, 'wb') as f:
            while True:
                frame_not_found_count = 0
                print('new request at %s\n' % now.strftime("%Y-%m-%d %H:%M:%S"))
                # r = requests.get(url, stream=True)
                r = requests.request('GET', url=url, stream=True, timeout=2)
                for chunk in r.iter_content(chunk_size=1024):
                    now = datetime.datetime.now(budapest_timezone)

                    if now.time().minute < prev_time.minute:
                        prev_local_file_path = local_file_path
                        local_file_path = now.strftime(local_file_path_template)
                        f.close()
                        print(f'uploading file to dropbox: {prev_local_file_path}')
                        upload_to_dropbox(
                            dbx,
                            prev_local_file_path,
                            os.path.join(remote_folder, os.path.basename(prev_local_file_path)))

                        print(f'starting writing: {local_file_path}')
                        f = open(local_file_path, 'wb')
                    prev_time = now
                    if chunk: # filter out keep-alive new chunks
                        fifo.write(chunk)
                        while True:
                            buffer = fifo.read()
                            start = find_seqs(buffer, header_start_seqs)
                            end = find_seqs(buffer, header_start_seqs, start+960)
                            print(f'frame size: {end-start}')

                            if end-start > 960:  # there is probably a broken mp3 frame at the beginning, finding the end of that
                                end = find_seqs(buffer, header_start_seqs, start+8)

                            # if (end-start) not in {-1, 1044, 1045, 960}:
                            #     print(f'start:{start}, end:{end}')
                            #     print(buffer[start:start+5].hex())

                            if end > -1:
                                to_file = buffer[start:end]
                                f.write(to_file)
                                to_keep = buffer[end:]
                                fifo.write(to_keep)
                                frame_not_found_count = 0
                            else:
                                fifo.write(buffer)
                                frame_not_found_count = frame_not_found_count+1
                                if frame_not_found_count > 2:
                                    print('frame not found')
                                    print(buffer)
                                break

    except Exception as inst:
        print(type(inst))    # the exception instance
        print(inst.args)     # arguments stored in .args
        print(inst)


token = 'nVqoOvFBafoAAAAAAAAAAeHEHFeM2KCDuPzZbsKjYaY7BkWzEUeJf8UL_A1x70Oa'

local_folder = r'/home/uglyculture/bartok_mp3s/'
local_folder = r'/'

remote_folder = r'/'
stream_url = r'https://icast.connectmedia.hu/4742/mr3hq.mp3'
stream_url = r'http://stream002.radio.hu:80/mr3hq.mp3'
local_file_path_template = r'bartok_mp3s/bartok_%Y-%m-%d_%H-%M.mp3'


with dropbox.Dropbox(token) as dbx:
    while True:
        download_stream(stream_url, local_file_path_template)
        sleep(0.05)
    # asyncio.run( download_stream(stream_url, local_file_path_template))