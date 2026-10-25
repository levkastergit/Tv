"""Сервер обновлений для «Русского ТВ»: раздаёт файлы из папки server на порту 40100."""
import functools
import http.server
import os

PORT = 40100
DIRECTORY = os.path.join(os.path.dirname(os.path.abspath(__file__)), "server")


class Handler(http.server.SimpleHTTPRequestHandler):
    # HTTP/1.1: соединение закрывает телевизор, дочитав файл, — иначе хвост файла может потеряться
    protocol_version = "HTTP/1.1"
    # Простаивающее соединение закрываем через минуту
    timeout = 60

    def list_directory(self, path):
        # Не показываем содержимое папки — только файлы по точному имени
        self.send_error(404)
        return None


http.server.ThreadingHTTPServer(("0.0.0.0", PORT), functools.partial(Handler, directory=DIRECTORY)).serve_forever()
