@echo off
setlocal
REM Direct clang build (no CMake / no Ninja).
REM CMake 4.x + Ninja hangs in this environment, but the NDK clang driver works.
REM usage: build_direct.bat [arm64-v8a|x86_64]   (default: arm64-v8a)

set ABI=%~1
if "%ABI%"=="" set ABI=arm64-v8a

if /I "%ABI%"=="arm64-v8a" (
  set TRIPLE=aarch64-linux-android24
) else if /I "%ABI%"=="x86_64" (
  set TRIPLE=x86_64-linux-android24
) else (
  echo [ERROR] unsupported ABI %ABI%
  exit /b 1
)

set SDK=C:\Users\15007\AppData\Local\Android\Sdk
set NDK=%SDK%\ndk\30.0.16248370
set CXX="%NDK%\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe"
set NCNN=%~dp0..\third_party\ncnn-20261020-android-vulkan\%ABI%
set OUT=%~dp0..\app\src\main\jniLibs\%ABI%

if not exist %CXX% (
  echo [ERROR] clang++ not found
  exit /b 1
)
if not exist "%NCNN%\lib\libncnn.a" (
  echo [ERROR] libncnn.a not found for %ABI%
  exit /b 1
)
if not exist "%OUT%" mkdir "%OUT%"

echo === compile %ABI% (%TRIPLE%) ===
%CXX% --target=%TRIPLE% -std=c++17 -O2 -fPIC -shared ^
  -fopenmp -fexceptions -fno-rtti ^
  -I"%NCNN%\include\ncnn" ^
  "%~dp0srjni.cpp" ^
  -o "%OUT%\libsrjni.so" ^
  -Wl,--start-group ^
  "%NCNN%\lib\libncnn.a" ^
  "%NCNN%\lib\libglslang.a" ^
  "%NCNN%\lib\libSPIRV.a" ^
  "%NCNN%\lib\libMachineIndependent.a" ^
  "%NCNN%\lib\libGenericCodeGen.a" ^
  "%NCNN%\lib\libOSDependent.a" ^
  "%NCNN%\lib\libglslang-default-resource-limits.a" ^
  -Wl,--end-group ^
  -static-libstdc++ -landroid -ljnigraphics -lvulkan -llog -lz -lm
if errorlevel 1 (
  echo [ERROR] build failed
  exit /b 1
)

echo === ok ===
for %%F in ("%OUT%\libsrjni.so") do echo %OUT%\libsrjni.so  %%~zF bytes
