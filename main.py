import tidalapi
from plyer import notification
from pywinauto import Desktop
import psutil
import os
import pypresence
import time
import logging
import threading
session = tidalapi.Session()
track = session.track()

def check_for_tidal():
    json_return = {
        'open': False,
        'playing': False,
        'song_playing': None,
    }
    windows = Desktop(backend="uia").windows()
    for w in windows:
        p_id = w.process_id()  
        process = psutil.Process(p_id)  
        if 'tidal' in process.name().lower():  
            json_return['open'] = True
            if w.window_text() == 'TIDAL':
                json_return['playing'] = False
                return json_return
            json_return['playing'] = True
            json_return['song_playing'] = w.window_text()
            return json_return
    return json_return


def login_saved(token_type, access_token, expiry_time, refresh_token): #functia asta nesimtita ca mi e lene sa fac altfel
    session.load_oauth_session(token_type, access_token, expiry_time, refresh_token)
def save_login(token_type, access_token, expiry_time, refresh_token): #meow
    with open('credentials.ini', 'w') as f:
        f.write(f'{token_type}\n')
        f.write(f'{access_token}\n')
        f.write(f'{expiry_time}\n')
        f.write(f'{refresh_token}\n')
        f.write(f'valid\n')
        # shiko shiko shiko shiko shiko shiko

offset = 0

#login :3
if os.path.getsize('credentials.ini') != 0:
    with open('credentials.ini', 'r') as f:
        lines = f.readlines()
        token_type = lines[0].strip()
        access_token = lines[1].strip()
        expiry_time = lines[2].strip()
        refresh_token = lines[3].strip()
        valid = lines[4].strip()
        login_saved(token_type, access_token, expiry_time, refresh_token)
        print(session.check_login())
else:
       session.login_oauth_simple()
       token_type = session.token_type
       access_token = session.access_token
       expiry_time = session.expiry_time
       refresh_token = session.refresh_token
       save_login(token_type, access_token, expiry_time, refresh_token)
       print(session.check_login())

session_id = session.session_id
song = session.search(check_for_tidal()['song_playing'])['top_hit']

artists = (
    [artist.name for artist in song.artists if artist.name is not None]
    if song.artists
    else None
)

album = song.album.name
print(album)

def start_rpc():
    client_id = 1407686593812103168
    rpc = pypresence.Presence(client_id)
    rpc.connect()
    rpc.update(
                activity_type=pypresence.ActivityType.LISTENING,
                details=song.name,
                state=", ".join(artists) if artists else "Unknown Artist",
                large_image=song.album.image() if song.album else "hightide_x1024",
                large_text=song.album.name if song.album else "DiscordRPC",
                small_image=song.artists[0].image() if song.album else None,
                small_text="DiscordRPC" if song.album else None,
                start=int(time.time()) - offset,
                end=int(time.time() + song.duration) if song.duration else None
                if song.duration
                else None,
                buttons=[
                    {
                        "label": "Listen to " + song.name,
                        "url": f"https://tidal.com/track/{song.id}"
                    },
                    {
                        "label": "Get DiscordRPC",
                        "url": "https://github.com/mousetz/tidalrpc",
                    },
                ]
            )
 




if song:
    print(f'Id: {song.id}')
    print(f'song: {song.name}')
    print(f'artist: {artists}')
    minutes, seconds = divmod(song.duration, 60)
    print(f'Duration (mm:ss): {minutes}:{seconds:02d}')
    print(f'Duration (seconds): {song.duration}')
    start_rpc()
    while True:
        time.sleep(0.1)


print(artists)




'''home = session.home()
session_id = session.session_id
user = session.user
user_id = user.id
print(user.id)
artist = track.id
print(artist)
name = track.name
print(name)
'''

