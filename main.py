from PySide6.QtCore import QTimer
import tidalapi
from plyer import notification
from pywinauto import Desktop
import psutil
import os
import pypresence
import time
import logging
from ui import create_window,on_toggle_show_artist
from PySide6.QtWidgets import QApplication, QMainWindow, QLabel, QWidget , QVBoxLayout
from PySide6.QtCore import Qt,QFile
from PySide6.QtUiTools import QUiLoader
import configparser

session = tidalapi.Session()
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s') 
logger = logging.getLogger(__name__)

def check_for_tidal():
    json_return = {
        'open': False,
        'playing': False,
        'song_playing': None,
    }
    try:
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
    except Exception as e:
        logger.error(f"Error checking for TIDAL: {e}")
    return json_return

def get_song():
    if check_for_tidal()['playing']:
        try:
            song = session.search(check_for_tidal()['song_playing'])['top_hit']
            return song 
        except Exception as e:
            logger.error(f"Error getting current song: {e}")
    return None


def login_saved(token_type, access_token, expiry_time, refresh_token): #functia asta nesimtita ca mi e lene sa fac altfel
    session.load_oauth_session(token_type, access_token, expiry_time, refresh_token)
def save_login(token_type, access_token, expiry_time, refresh_token): #meow
    with open('credentials.ini', 'w') as f:
        f.write(f'{token_type}\n')
        f.write(f'{access_token}\n')
        f.write(f'{expiry_time}\n')
        f.write(f'{refresh_token}\n')
        # shiko shiko shiko shiko shiko shiko


#login :3
if os.path.exists('credentials.ini') and os.path.getsize('credentials.ini') != 0:
    try:
        with open('credentials.ini', 'r') as f:
            lines = f.readlines()
            token_type = lines[0].strip()
            access_token = lines[1].strip()
            expiry_time = lines[2].strip()
            refresh_token = lines[3].strip()
            login_saved(token_type, access_token, expiry_time, refresh_token)
            logger.info(f"Login status: {session.check_login()}")
    except Exception as e:
        logger.error(f"Error loading saved login: {e}")
        session.login_oauth_simple()
        token_type = session.token_type
        access_token = session.access_token
        expiry_time = session.expiry_time
        refresh_token = session.refresh_token
        save_login(token_type, access_token, expiry_time, refresh_token)
        logger.info(f"Login status: {session.check_login()}")
else:
       session.login_oauth_simple()
       token_type = session.token_type
       access_token = session.access_token
       expiry_time = session.expiry_time
       refresh_token = session.refresh_token
       save_login(token_type, access_token, expiry_time, refresh_token)
       logger.info(f"Login status: {session.check_login()}")


client_id = 1411287876062416908
rpc = pypresence.Presence(client_id)
rpc.connect()
show_artist=True

def update_rpc(song):
    if not song:
        return
    
    artists = (
        [artist.name for artist in song.artists if artist.name is not None]
        if song.artists
        else None
    )
    config=configparser.ConfigParser()
    config.read("settings.ini")
    my_list_str=config["UI"]["my_list"].split(",")
    my_list=[s == "True" if s in ["True","False"] else int(s) for s in my_list_str]
    try:
        rpc.update(
            activity_type=pypresence.ActivityType.LISTENING,
            details=song.name,
            state=", ".join(artists) if artists else "Unknown Artist",
            large_image=song.album.image() if song.album else "hightide_x1024",
            large_text=song.album.name if song.album else "DiscordRPC",
            small_image=song.artists[0].image() if my_list[0] and song.album and song.artists and song.artists[0].image()!="https://resources.tidal.com/images/1e01cdb6/f15d/4d8b/8440/a047976c1cac/320x320.jpg" else None,
            small_text="DiscordRPC" if song.album else None,
            start=int(time.time() + 0.5),
            end=int(time.time() + song.duration) if song.duration else None,
            buttons=[
                {
                    "label": "Listen to the song",
                    "url": f"https://tidal.com/browse/track/{song.id}?u"
                },
                {
                    "label": "Get DiscordRPC",
                    "url": "https://github.com/mousetz/tidalrpc",
                },
            ] if my_list[1] else None
        )
        logger.info(f"RPC updated: {song.name} by {", ".join(artists)}")
    except Exception as e:
        logger.error(f"Error updating RPC: {e}")

previous_song_id = None
def tick():
    global previous_song_id
    try:
        current_song = get_song()
        
        if current_song:
            if not previous_song_id or current_song.id != previous_song_id and current_song:
                artists = (
                    [artist.name for artist in current_song.artists if artist.name is not None]
                    if current_song.artists
                    else None
                )
                
                
                update_rpc(current_song)
                previous_song_id = current_song.id
        else:
            previous_song_id = None
            logger.info("No song currently playing")
    except Exception as e:
        logger.error(f"Error in main loop: {e}")
        


def main():
    window, app = create_window()
    window.show()
    timer = QTimer()
    timer.timeout.connect(tick) 
    timer.start(1000) 
    app.exec()
    

main()