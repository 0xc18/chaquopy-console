from six.moves import input
from tqdm import tqdm
from time import sleep

def main():
    print("Enter your name, or an empty line to exit.")
    while True:
        try:
            name = input()
            for i in range(10):
                sleep(1)
                print("sleep",i, end="\r")
        except EOFError:
            break
        if not name:
            break
        print("Hello {}!".format(name))
