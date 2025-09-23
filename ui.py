from logging import config
from PySide6.QtWidgets import QApplication, QMainWindow, QLabel, QWidget , QVBoxLayout,QMessageBox
from PySide6.QtCore import Qt,QFile
from PySide6.QtUiTools import QUiLoader
from PySide6.QtGui import QIcon
import configparser


def on_toggle_show_artist():
    config=configparser.ConfigParser()
    config.read("settings.ini")
    my_list_str=config["UI"]["my_list"].split(",")
    my_list=[s == "True" if s in ["True","False"] else int(s) for s in my_list_str]
    my_list[0]=False if my_list[0] else True
    config["UI"]={}
    config["UI"]["my_list"]=",".join(map(str, my_list))
    with open("settings.ini", "w") as configfile:
        config.write(configfile)

def on_toggle_show_buttons():
    config=configparser.ConfigParser()
    config.read("settings.ini")
    my_list_str=config["UI"]["my_list"].split(",")
    my_list=[s == "True" if s in ["True","False"] else int(s) for s in my_list_str]
    my_list[1]=False if my_list[1] else True
    config["UI"]={}
    config["UI"]["my_list"]=",".join(map(str, my_list))
    with open("settings.ini", "w") as configfile:
        config.write(configfile)
def create_window():
    global window
    app=QApplication()
    loader=QUiLoader()
    file=QFile("QTdesign.ui")
    file.open(QFile.ReadOnly)
    window=loader.load(file)
    file.close()

    #set default variables for check_box
    config=configparser.ConfigParser()
    config.read("settings.ini")
    my_list_str=config["UI"]["my_list"].split(",")
    my_list=[s == "True" if s in ["True","False"] else int(s) for s in my_list_str]
    window.checkBox.setChecked(my_list[0])
    window.checkBox_2.setChecked(my_list[1])

    window.setWindowIcon(QIcon("tidal_icon.png"))
    window.checkBox.toggled.connect(on_toggle_show_artist)
    window.checkBox_2.toggled.connect(on_toggle_show_buttons)

    show_popup("TEST")

    return window,app

def show_popup(message):
    msg = QMessageBox()
    msg.setWindowTitle("Not logged in!")
    msg.setText(message)
    msg.exec()  