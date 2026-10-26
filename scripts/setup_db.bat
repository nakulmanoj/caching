@echo off
set PGPASSWORD=postgres
set PSQL="C:\Program Files\PostgreSQL\18\bin\psql.exe"

echo === Setting up WealthWise database ===
%PSQL% -U postgres -h 127.0.0.1 -p 5432 -c "DROP DATABASE IF EXISTS wealthwise;"
%PSQL% -U postgres -h 127.0.0.1 -p 5432 -c "DROP USER IF EXISTS wealthwise;"
%PSQL% -U postgres -h 127.0.0.1 -p 5432 -c "CREATE USER wealthwise WITH PASSWORD 'wealthwise';"
%PSQL% -U postgres -h 127.0.0.1 -p 5432 -c "CREATE DATABASE wealthwise OWNER wealthwise;"
echo === Database created ===
%PSQL% -U postgres -h 127.0.0.1 -p 5432 -c "\l wealthwise"
