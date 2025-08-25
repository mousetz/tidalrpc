import tidalapi
from plyer import notification
import os
import pypresence

session = tidalapi.Session()


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
        # ok am inteles si oricand vreau pot sa intru? sau tre sa ai si tu deschis
        # nu cred ca tre sa il am deschis k staus tai sa incercam

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
        print(session.load_oauth_session(token_type, access_token, refresh_token, expiry_time))
else:
       session.login_oauth_simple()
       token_type = session.token_type
       access_token = session.access_token
       expiry_time = session.expiry_time
       refresh_token = session.refresh_token
       print(session.load_oauth_session(token_type, access_token, refresh_token, expiry_time))
       save_login(token_type, access_token, expiry_time, refresh_token)

home = session.home()
session_id = session.session_id
user = session.user
user_id = user.id
print(user.id)
