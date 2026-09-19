@echo off
title K2 Horizon - ChatGPT Web Interface & Latency Benchmark
echo ======================================================================
echo  Starting K2 Horizon AI Web Server on your AMD Ryzen 7 (32GB RAM)...
echo  URL: http://localhost:8000
echo ======================================================================
start http://localhost:8000
python server.py
pause
