from __future__ import annotations

import sys
import threading
from pathlib import Path
import tkinter as tk
from tkinter import ttk, messagebox

import esptool
from serial.tools import list_ports


def app_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


def bundle_dir() -> Path:
    return app_dir() / "bundle"


def firmware_paths() -> tuple[Path, Path, Path]:
    root = bundle_dir()
    return root / "bootloader.bin", root / "partitions.bin", root / "firmware.bin"


class FlasherApp(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("ESP32 Car Installer")
        self.geometry("620x430")
        self.minsize(620, 430)

        self.port_var = tk.StringVar()
        self.erase_var = tk.BooleanVar(value=True)
        self.status_var = tk.StringVar(value="ESP32-CAM을 USB-UART에 연결하세요.")

        self._build_ui()
        self.refresh_ports()

    def _build_ui(self) -> None:
        frame = ttk.Frame(self, padding=18)
        frame.pack(fill="both", expand=True)

        ttk.Label(frame, text="ESP32 Car — First Install", font=("Segoe UI", 18, "bold")).pack(anchor="w")
        ttk.Label(
            frame,
            text=(
                "최초 1회만 PC로 설치합니다. 이후 업데이트는 Android 앱에서 OTA로 진행합니다.\n"
                "ESP32-CAM 보드라면 업로드 모드 진입을 위해 GPIO0↔GND 연결 후 Reset이 필요할 수 있습니다."
            ),
            wraplength=570,
        ).pack(anchor="w", pady=(8, 18))

        row = ttk.Frame(frame)
        row.pack(fill="x")
        ttk.Label(row, text="Serial port").pack(side="left")
        self.port_combo = ttk.Combobox(row, textvariable=self.port_var, state="readonly", width=28)
        self.port_combo.pack(side="left", padx=10)
        ttk.Button(row, text="새로고침", command=self.refresh_ports).pack(side="left")

        ttk.Checkbutton(
            frame,
            text="설치 전에 Flash 전체 지우기 (최초 설치 권장)",
            variable=self.erase_var,
        ).pack(anchor="w", pady=14)

        self.flash_button = ttk.Button(frame, text="ESP32에 설치", command=self.start_flash)
        self.flash_button.pack(anchor="w", pady=(4, 12))

        self.progress = ttk.Progressbar(frame, mode="indeterminate")
        self.progress.pack(fill="x", pady=(0, 12))
        ttk.Label(frame, textvariable=self.status_var, wraplength=570).pack(anchor="w")

        ttk.Separator(frame).pack(fill="x", pady=18)
        ttk.Label(
            frame,
            text=(
                "설치 완료 후 GPIO0↔GND를 분리하고 Reset/전원을 다시 넣으세요. "
                "Android Bluetooth 설정에서 ESP32_CAM_RC를 페어링하면 됩니다."
            ),
            wraplength=570,
        ).pack(anchor="w")

    def refresh_ports(self) -> None:
        ports = [p.device for p in list_ports.comports()]
        self.port_combo["values"] = ports
        if ports and self.port_var.get() not in ports:
            self.port_var.set(ports[0])
        elif not ports:
            self.port_var.set("")
            self.status_var.set("Serial port를 찾지 못했습니다. USB-UART 드라이버/연결을 확인하세요.")

    def start_flash(self) -> None:
        port = self.port_var.get().strip()
        if not port:
            messagebox.showerror("Port 없음", "ESP32가 연결된 COM port를 선택하세요.")
            return

        bootloader, partitions, firmware = firmware_paths()
        missing = [p.name for p in (bootloader, partitions, firmware) if not p.exists()]
        if missing:
            messagebox.showerror("Firmware 누락", "bundle 폴더에 다음 파일이 없습니다: " + ", ".join(missing))
            return

        self.flash_button.config(state="disabled")
        self.progress.start(12)
        self.status_var.set("ESP32 연결 및 Flash 준비 중…")
        threading.Thread(target=self._flash_worker, args=(port,), daemon=True).start()

    def _flash_worker(self, port: str) -> None:
        bootloader, partitions, firmware = firmware_paths()
        try:
            if self.erase_var.get():
                self._set_status("Flash 전체 삭제 중…")
                esptool.main(["--chip", "esp32", "--port", port, "erase-flash"])

            self._set_status("Firmware 설치 중…")
            esptool.main([
                "--chip", "esp32",
                "--port", port,
                "--baud", "460800",
                "--before", "default-reset",
                "--after", "hard-reset",
                "write-flash", "-z",
                "0x1000", str(bootloader),
                "0x8000", str(partitions),
                "0x10000", str(firmware),
            ])
        except SystemExit as exc:
            code = exc.code if isinstance(exc.code, int) else 1
            if code != 0:
                self._finish(False, f"esptool 종료 코드 {code}. GPIO0↔GND/Reset/COM port를 확인하세요.")
                return
        except Exception as exc:  # noqa: BLE001
            self._finish(False, f"설치 실패: {exc}")
            return

        self._finish(True, "설치 완료. GPIO0↔GND를 분리하고 ESP32를 Reset하세요. 이후 펌웨어 업데이트는 휴대폰 앱에서 가능합니다.")

    def _set_status(self, text: str) -> None:
        self.after(0, lambda: self.status_var.set(text))

    def _finish(self, ok: bool, text: str) -> None:
        def apply() -> None:
            self.progress.stop()
            self.flash_button.config(state="normal")
            self.status_var.set(text)
            (messagebox.showinfo if ok else messagebox.showerror)("완료" if ok else "실패", text)
        self.after(0, apply)


if __name__ == "__main__":
    FlasherApp().mainloop()
