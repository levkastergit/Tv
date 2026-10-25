@echo off
rem Запускает сервер обновлений «Русского ТВ» в свёрнутом окне. Закрыть окно — остановить сервер.
rem Именно python.exe: ему разрешены входящие подключения в брандмауэре Windows.
start "Server obnovleniy Russkoe TV" /min "C:\Users\roblox\AppData\Local\Programs\Python\Python312\python.exe" -I "%~dp0serve.py"
