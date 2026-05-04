# Для запуска:

## 1. Запустить Docker-контейнер из корня проекта:
```powershell
docker compose up -d
```

## 2. Выбрать файл для анализа и запустить программу:
```powershell
.\gradlew.bat run --args="path\to_file.c"
```