@echo off
setlocal
REM Build libsrjni.so for one ABI with the SDK's NDK + CMake.
REM usage: build.bat [arm64-v8a|x86_64]   (default: arm64-v8a)

set ABI=%~1
if "%ABI%"=="" set ABI=arm64-v8a

set SDK=C:\Users\15007\AppData\Local\Android\Sdk
set NDK=%SDK%\ndk\30.0.16248370
set CMAKE_DIR=%SDK%\cmake\4.1.2\bin
set OUT=%~dp0..\app\src\main\jniLibs\%ABI%

if not exist "%NDK%\build\cmake\android.toolchain.cmake" (
  echo [ERROR] NDK not found at %NDK%
  exit /b 1
)
if not exist "%CMAKE_DIR%\cmake.exe" (
  echo [ERROR] cmake not found at %CMAKE_DIR%
  exit /b 1
)

echo === configuring %ABI% ===
"%CMAKE_DIR%\cmake.exe" -S "%~dp0." -B "%~dp0build\%ABI%" -G Ninja ^
  -DCMAKE_TOOLCHAIN_FILE="%NDK%\build\cmake\android.toolchain.cmake" ^
  -DANDROID_ABI=%ABI% ^
  -DANDROID_PLATFORM=android-24 ^
  -DANDROID_STL=c++_static ^
  -DCMAKE_BUILD_TYPE=Release ^
  -DCMAKE_MAKE_PROGRAM="%CMAKE_DIR%\ninja.exe"
if errorlevel 1 exit /b 1

echo === building %ABI% ===
"%CMAKE_DIR%\cmake.exe" --build "%~dp0build\%ABI%"
if errorlevel 1 exit /b 1

if not exist "%OUT%" mkdir "%OUT%"
copy /Y "%~dp0build\%ABI%\libsrjni.so" "%OUT%\libsrjni.so" >nul
if errorlevel 1 exit /b 1

echo === ok: %OUT%\libsrjni.so ===
for %%F in ("%OUT%\libsrjni.so") do echo     size: %%~zF bytes
