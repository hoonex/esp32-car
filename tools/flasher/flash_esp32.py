from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import threading
from pathlib import Path
import tkinter as tk
from tkinter import messagebox, ttk

from serial.tools import list_ports

CORE_VERSION = "3.3.0"
BOARD_BASE = "esp32:esp32:esp32cam"
RECOVERY_FQBN = "esp32:esp32:esp32cam:EraseFlash=all"
ESPRESSIF_INDEX = "https://espressif.github.io/arduino-esp32/package_esp32_index.json"


def resource_root() -> Path:
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS")).resolve()
    return Path(__file__).resolve().parents[2]


def executable_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


def find_arduino_cli() -> Path | None:
    root = resource_root()
    candidates = [
        root / "arduino-cli.exe",
        root / "arduino-cli",
        executable_dir() / "arduino-cli.exe",
        executable_dir() / "arduino-cli",
    ]
    found = shutil.which("arduino-cli")
    if found:
        candidates.append(Path(found))

    program_files = os.environ.get("ProgramFiles")
    local_app_data = os.environ.get("LOCALAPPDATA")
    program_files_x86 = os.environ.get("ProgramFiles(x86)")
    if program_files:
        candidates.append(Path(program_files) / "Arduino IDE/resources/app/lib/backend/resources/arduino-cli.exe")
    if local_app_data:
        candidates.append(Path(local_app_data) / "Programs/Arduino IDE/resources/app/lib/backend/resources/arduino-cli.exe")
    if program_files_x86:
        candidates.append(Path(program_files_x86) / "Arduino IDE/resources/app/lib/backend/resources/arduino-cli.exe")

    for candidate in candidates:
        try:
            if candidate.is_file():
                return candidate.resolve()
        except OSError:
            continue
    return None


def source_pair() -> tuple[Path, Path] | None:
    root = resource_root()
    packaged = root / "sketch/ESP32_CAM_RC_Controller"
    repo = root / "firmware"
    for base in (packaged, repo):
        ino = base / "ESP32_CAM_RC_Controller.ino"
        partitions = base / "partitions.csv"
        if ino.is_file() and partitions.is_file():
            return ino, partitions
    return None


def self_test() -> int:
    cli = find_arduino_cli()
    pair = source_pair()
    if cli is None:
        print("SELF_TEST_FAIL: arduino-cli not found")
        return 2
    if pair is None:
        print("SELF_TEST_FAIL: .ino + partitions.csv pair not found")
        return 3
    print(f"SELF_TEST_OK cli={cli}")
    print(f"SELF_TEST_OK ino={pair[0]}")
    print(f"SELF_TEST_OK partitions={pair[1]}")
    return 0


class RecoveryApp(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("ESP32 Car — Arduino Full Recovery")
        self.geometry("760x620")
        self.minsize(720, 560)

        self.cli = find_arduino_cli()
        self.sources = source_pair()
        self.port_var = tk.StringVar()
        self.status_var = tk.StringVar(value="ESP32-CAM을 USB 프로그래머/USB-UART에 연결하세요.")

        self._build_ui()
        self.refresh_ports()
        self._append_log("Recovery path: FULL CHIP ERASE -> Arduino compile -> Arduino upload")
        self._append_log(f"FQBN: {RECOVERY_FQBN}")
        if self.cli:
            self._append_log(f"arduino-cli: {self.cli}")
        else:
            self._append_log("ERROR: arduino-cli를 찾지 못했습니다.")
        if self.sources:
            self._append_log(f"Sketch: {self.sources[0]}")
            self._append_log(f"Partitions: {self.sources[1]}")
        else:
            self._append_log("ERROR: .ino와 partitions.csv 쌍을 찾지 못했습니다.")

    def _build_ui(self) -> None:
        frame = ttk.Frame(self, padding=18)
        frame.pack(fill="both", expand=True)

        ttk.Label(frame, text="ESP32 Car — Arduino Full Recovery", font=("Segoe UI", 18, "bold")).pack(anchor="w")
        ttk.Label(
            frame,
            text=(
                "기존 raw .bin 플래셔가 아니라 Arduino 공식 업로드 경로를 사용합니다. "
                "Flash 전체를 지운 뒤 ESP32_CAM_RC_Controller.ino와 같은 폴더의 partitions.csv를 함께 컴파일/업로드합니다."
            ),
            wraplength=700,
        ).pack(anchor="w", pady=(8, 14))

        row = ttk.Frame(frame)
        row.pack(fill="x")
        ttk.Label(row, text="Serial port").pack(side="left")
        self.port_combo = ttk.Combobox(row, textvariable=self.port_var, state="readonly", width=30)
        self.port_combo.pack(side="left", padx=10)
        ttk.Button(row, text="새로고침", command=self.refresh_ports).pack(side="left")

        ttk.Label(
            frame,
            text="FULL ERASE는 항상 켜져 있습니다. 복구 과정에서 기존 Flash 내용/NVS/Wi-Fi 설정은 지워집니다.",
        ).pack(anchor="w", pady=(12, 8))

        self.flash_button = ttk.Button(frame, text="전체 지우기 + Arduino .ino Full Flash", command=self.start_recovery)
        self.flash_button.pack(anchor="w", pady=(2, 10))

        self.progress = ttk.Progressbar(frame, mode="indeterminate")
        self.progress.pack(fill="x", pady=(0, 8))
        ttk.Label(frame, textvariable=self.status_var, wraplength=700).pack(anchor="w", pady=(0, 10))

        log_frame = ttk.Frame(frame)
        log_frame.pack(fill="both", expand=True)
        self.log = tk.Text(log_frame, height=18, wrap="word", state="disabled", font=("Consolas", 9))
        scroll = ttk.Scrollbar(log_frame, orient="vertical", command=self.log.yview)
        self.log.configure(yscrollcommand=scroll.set)
        self.log.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")

        ttk.Label(
            frame,
            text=(
                "연결 실패 시: Serial Monitor를 닫고, GPIO0→GND 상태에서 Reset/전원을 다시 넣은 뒤 재시도하세요. "
                "성공 후에는 GPIO0-GND를 해제하고 다시 Reset/전원을 넣어 정상 부팅합니다."
            ),
            wraplength=700,
        ).pack(anchor="w", pady=(12, 0))

    def _append_log(self, text: str) -> None:
        def apply() -> None:
            self.log.configure(state="normal")
            self.log.insert("end", text.rstrip() + "\n")
            self.log.see("end")
            self.log.configure(state="disabled")
        if threading.current_thread() is threading.main_thread():
            apply()
        else:
            self.after(0, apply)

    def refresh_ports(self) -> None:
        ports = [p.device for p in list_ports.comports()]
        self.port_combo["values"] = ports
        if ports and self.port_var.get() not in ports:
            self.port_var.set(ports[0])
        elif not ports:
            self.port_var.set("")
            self.status_var.set("Serial port를 찾지 못했습니다. USB-UART 연결/드라이버를 확인하세요.")

    def start_recovery(self) -> None:
        port = self.port_var.get().strip()
        if not port:
            messagebox.showerror("Port 없음", "ESP32-CAM이 연결된 COM port를 선택하세요.")
            return
        if self.cli is None:
            messagebox.showerror("Arduino CLI 없음", "arduino-cli를 찾지 못했습니다. 패키지를 다시 받거나 Arduino IDE 2.x를 설치하세요.")
            return
        if self.sources is None:
            messagebox.showerror("Recovery source 없음", ".ino와 partitions.csv를 함께 찾지 못했습니다. .ino 단독 업로드는 하지 마세요.")
            return

        self.flash_button.configure(state="disabled")
        self.progress.start(12)
        self.status_var.set("Arduino full recovery 준비 중…")
        self._append_log("=" * 72)
        self._append_log(f"START port={port}")
        threading.Thread(target=self._recovery_worker, args=(port,), daemon=True).start()

    def _run(self, args: list[str], *, allow_failure: bool = False) -> tuple[int, str]:
        assert self.cli is not None
        cmd = [str(self.cli), *args]
        self._append_log("> " + " ".join(cmd))
        process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        collected: list[str] = []
        assert process.stdout is not None
        for line in process.stdout:
            collected.append(line)
            self._append_log(line)
        code = process.wait()
        output = "".join(collected)
        if code != 0 and not allow_failure:
            raise RuntimeError(f"arduino-cli exit {code}\n{output[-5000:]}")
        return code, output

    def _ensure_core(self) -> None:
        self._set_status(f"Espressif Arduino core {CORE_VERSION} 확인 중…")
        self._run(["config", "init"], allow_failure=True)
        self._run(["config", "add", "board_manager.additional_urls", ESPRESSIF_INDEX], allow_failure=True)
        self._run(["core", "update-index"])
        _, core_list = self._run(["core", "list"])
        expected = f"esp32:esp32 {CORE_VERSION}"
        if expected not in " ".join(core_list.split()):
            self._run(["core", "install", f"esp32:esp32@{CORE_VERSION}"])

        _, details = self._run(["board", "details", "--fqbn", BOARD_BASE])
        if "EraseFlash" not in details:
            raise RuntimeError("ESP32 core does not expose EraseFlash; refusing non-full recovery flash.")

    def _recovery_worker(self, port: str) -> None:
        assert self.sources is not None
        ino, partitions = self.sources
        temp_root = Path(tempfile.mkdtemp(prefix="esp32-car-full-recovery-"))
        sketch = temp_root / "ESP32_CAM_RC_Controller"
        sketch.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ino, sketch / "ESP32_CAM_RC_Controller.ino")
        shutil.copy2(partitions, sketch / "partitions.csv")

        try:
            self._ensure_core()
            self._set_status("정확한 .ino + OTA partition table 컴파일 중…")
            self._run(["compile", "--fqbn", RECOVERY_FQBN, str(sketch)])

            self._set_status("Flash 전체 삭제 + Arduino 업로드 중…")
            self._run(["upload", "--verbose", "--port", port, "--fqbn", RECOVERY_FQBN, str(sketch)])
        except Exception as exc:  # noqa: BLE001
            error = str(exc)
            lower = error.lower()
            if "failed to connect" in lower or "no serial data received" in lower or "wrong boot mode" in lower:
                hint = "GPIO0→GND 후 Reset/전원을 다시 넣고 재시도하세요."
            elif "access is denied" in lower or "permission" in lower or "resource busy" in lower:
                hint = "Serial Monitor/다른 프로그램이 COM port를 잡고 있는지 확인하세요."
            else:
                hint = "아래 로그의 마지막 오류가 실제 실패 원인입니다."
            self._finish(False, f"Full flash 실패. {hint}")
            self._append_log(f"FAIL: {error}")
            return
        finally:
            shutil.rmtree(temp_root, ignore_errors=True)

        self._finish(
            True,
            "Full flash 완료. GPIO0-GND를 사용했다면 해제하고 Reset/전원을 다시 넣으세요. ESP32_CAM_RC가 검색되면 정상 부팅입니다.",
        )

    def _set_status(self, text: str) -> None:
        self.after(0, lambda: self.status_var.set(text))

    def _finish(self, ok: bool, text: str) -> None:
        def apply() -> None:
            self.progress.stop()
            self.flash_button.configure(state="normal")
            self.status_var.set(text)
            self._append_log(("SUCCESS: " if ok else "ERROR: ") + text)
            (messagebox.showinfo if ok else messagebox.showerror)("완료" if ok else "실패", text)
        self.after(0, apply)


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    RecoveryApp().mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
