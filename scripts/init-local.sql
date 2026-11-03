-- Run using an existing local MySQL account that can create schemas.
-- This creates only RepoPilot's isolated schema; Flyway owns its tables.
CREATE DATABASE IF NOT EXISTS repopilot
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
