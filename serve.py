"""Сервер обновлений для «Русского ТВ»: раздаёт файлы из папки server на порту 40100
и раз в 3 часа готовит телепрограмму epg.json для каналов из server/channels.json."""
import datetime
import functools
import gzip
import http.server
import json
import os
import sys
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET

# Для проверки можно запустить отдельный сервер: python serve.py <порт> <папка>
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 40100
DIRECTORY = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "server")

# Бесплатная телепрограмма в формате XMLTV (облегчённая версия, ~15 МБ)
EPG_SOURCE = "https://iptvx.one/EPG_LITE"
EPG_REFRESH_SECONDS = 3 * 3600
# Сколько программы отдавать телевизору: чуть назад (текущая передача) и сутки вперёд
EPG_BEHIND = datetime.timedelta(hours=3)
EPG_AHEAD = datetime.timedelta(hours=24)


class Handler(http.server.SimpleHTTPRequestHandler):
    # HTTP/1.1: соединение закрывает телевизор, дочитав файл, — иначе хвост файла может потеряться
    protocol_version = "HTTP/1.1"
    # Простаивающее соединение закрываем через минуту
    timeout = 60

    def list_directory(self, path):
        # Не показываем содержимое папки — только файлы по точному имени
        self.send_error(404)
        return None


def parse_time(value):
    """XMLTV: '20261025193000 +0300' -> время в миллисекундах."""
    return int(datetime.datetime.strptime(value.strip(), "%Y%m%d%H%M%S %z").timestamp() * 1000)


def build_epg():
    with open(os.path.join(DIRECTORY, "channels.json"), encoding="utf-8") as f:
        wanted = {c["epg"] for c in json.load(f) if c.get("epg")}
    now = datetime.datetime.now(datetime.timezone.utc)
    start_ms = int((now - EPG_BEHIND).timestamp() * 1000)
    end_ms = int((now + EPG_AHEAD).timestamp() * 1000)

    request = urllib.request.Request(EPG_SOURCE, headers={"User-Agent": "RusskoeTV-server"})
    programmes = {}
    with urllib.request.urlopen(request, timeout=300) as response:
        for _, el in ET.iterparse(gzip.GzipFile(fileobj=response), events=("end",)):
            if el.tag == "programme" and el.get("channel") in wanted and el.get("stop"):
                start, stop = parse_time(el.get("start")), parse_time(el.get("stop"))
                if stop > start_ms and start < end_ms:
                    title = (el.findtext("title") or "").strip()
                    programmes.setdefault(el.get("channel"), []).append([start, stop, title])
            if el.tag in ("programme", "channel"):
                el.clear()
    for items in programmes.values():
        items.sort()

    # Пишем во временный файл и подменяем — телевизор никогда не получит недописанный файл
    target = os.path.join(DIRECTORY, "epg.json")
    with open(target + ".tmp", "w", encoding="utf-8") as f:
        json.dump({"updated": int(time.time() * 1000), "channels": programmes}, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(target + ".tmp", target)
    print(f"{datetime.datetime.now():%Y-%m-%d %H:%M} телепрограмма: {len(programmes)} из {len(wanted)} каналов", flush=True)


def refresh_epg_forever():
    while True:
        try:
            build_epg()
        except Exception as e:
            print(f"{datetime.datetime.now():%Y-%m-%d %H:%M} не удалось обновить телепрограмму: {e}", flush=True)
        time.sleep(EPG_REFRESH_SECONDS)


threading.Thread(target=refresh_epg_forever, daemon=True).start()
http.server.ThreadingHTTPServer(("0.0.0.0", PORT), functools.partial(Handler, directory=DIRECTORY)).serve_forever()
