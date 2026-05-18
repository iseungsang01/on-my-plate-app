from pathlib import Path
import shutil
import sys

ROOT = Path.cwd()
TARGET = ROOT / "gradlew.bat"

GRADLEW_BAT = '@rem\n@rem Copyright 2015 the original author or authors.\n@rem\n@rem Licensed under the Apache License, Version 2.0 (the "License");\n@rem you may not use this file except in compliance with the License.\n@rem You may obtain a copy of the License at\n@rem\n@rem      https://www.apache.org/licenses/LICENSE-2.0\n@rem\n@rem Unless required by applicable law or agreed to in writing, software\n@rem distributed under the License is distributed on an "AS IS" BASIS,\n@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n@rem See the License for the specific language governing permissions and\n@rem limitations under the License.\n@rem\n@rem SPDX-License-Identifier: Apache-2.0\n@rem\n\n@if "%DEBUG%"=="" @echo off\n@rem ##########################################################################\n@rem\n@rem  Gradle startup script for Windows\n@rem\n@rem ##########################################################################\n\nif "%OS%"=="Windows_NT" setlocal\n\nset DIRNAME=%~dp0\nif "%DIRNAME%"=="" set DIRNAME=.\nset APP_BASE_NAME=%~n0\nset APP_HOME=%DIRNAME%\n\nfor %%i in ("%APP_HOME%") do set APP_HOME=%%~fi\n\nset DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"\n\nif defined JAVA_HOME goto findJavaFromJavaHome\n\nset JAVA_EXE=java.exe\n%JAVA_EXE% -version >NUL 2>&1\nif %ERRORLEVEL% equ 0 goto execute\n\necho.\necho ERROR: JAVA_HOME is not set and no \'java\' command could be found in your PATH.\necho.\necho Please set the JAVA_HOME variable in your environment to match the\necho location of your Java installation.\n\ngoto fail\n\n:findJavaFromJavaHome\nset JAVA_HOME=%JAVA_HOME:"=%\nset JAVA_EXE=%JAVA_HOME%/bin/java.exe\n\nif exist "%JAVA_EXE%" goto execute\n\necho.\necho ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%\necho.\necho Please set the JAVA_HOME variable in your environment to match the\necho location of your Java installation.\n\ngoto fail\n\n:execute\nset CLASSPATH=%APP_HOME%\\gradle\\wrapper\\gradle-wrapper.jar\n\n"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*\n\n:end\nif %ERRORLEVEL% equ 0 goto mainEnd\n\n:fail\nset EXIT_CODE=%ERRORLEVEL%\nif %EXIT_CODE% equ 0 set EXIT_CODE=1\nif not ""=="%GRADLE_EXIT_CONSOLE%" exit %EXIT_CODE%\nexit /b %EXIT_CODE%\n\n:mainEnd\nif "%OS%"=="Windows_NT" endlocal\n\n:omega\n'

def ok(msg: str) -> None:
    print(f"[OK] {msg}")

def fail(msg: str) -> None:
    print(f"[FAIL] {msg}")
    sys.exit(1)

def backup(path: Path) -> None:
    bak = path.with_suffix(path.suffix + ".bak")
    if not bak.exists():
        shutil.copy2(path, bak)
        ok(f"backup created: {bak}")
    else:
        ok(f"backup exists: {bak}")

if not TARGET.exists():
    fail(f"missing file: {TARGET}")

current = TARGET.read_text(encoding="utf-8", errors="replace")
if "org.gradle.wrapper.GradleWrapperMain" in current and current.strip():
    ok("gradlew.bat already contains a Gradle wrapper launcher")
    sys.exit(0)

if current.strip():
    fail("gradlew.bat is not empty but does not look like a Gradle wrapper launcher; inspect manually")

backup(TARGET)
TARGET.write_text(GRADLEW_BAT, encoding="utf-8", newline="\r\n")
ok("restored gradlew.bat Windows wrapper launcher")
ok("gradlew.bat empty-file fix complete")
