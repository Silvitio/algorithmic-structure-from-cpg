# Для запуска

Все команды прописываются из корня проекта.

## 1. Запустить Docker-контейнер
```powershell
docker compose up -d
```

## 2. Выбрать файл для анализа и запустить программу
### Для Windows:
```powershell
.\gradlew.bat run --args="code_examples\main_example.c"
```

### Для Linux/MacOS:
```powershell
./gradlew run --args="code_examples/main_example.c"
```

Если при запуске всплывает ошибка permission denied:
```powershell
chmod +x ./gradlew
```