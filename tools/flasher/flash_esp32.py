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


def firmware_paths() -> tuple[Path, Path, Path, Path]:
    root = bundle_dir()
    return (
        root / "bootloader.bin",
        root / "partitions.bin",
        root / "boot_app0.bin",
        root / "firmware.bin",
    )


class FlasherApp(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("ESP32 Car Installer")
        self.geometry("640x455")
        self.minsize(640, 455)

        self.port_var = tk.StringVar()
        self.erase_var = tk.BooleanVar(value=True)
        self.status_var = tk.StringVar(value="AI Thinker ESP32-CAM을 USB 프로그래머/USB-UART에 연결하세요.")

        self._build_ui()
        self.refresh_ports()

    def _build_ui(self) -> None:
        frame = ttk.Frame(self, padding=18)
        frame.pack(fill="both", expand=True)

        ttk.Label(frame, text="ESP32 Car — First Install", font=("Segoe UI", 18, "bold")).pack(anchor="w")
        ttk.Label(
            frame,
            text=(
                "Arduino IDE와 같은 4-image flash layout으로 최초 설치합니다. 이후 펌웨어 업데이트는 Android 앱에서 OTA로 진행합니다.\n"
                "ESP32-CAM-MB/자동 Reset 지원 프로그래머는 보통 그대로 진행하면 됩니다. 연결이 안 될 때만 보드의 BOOT/IO0 방식으로 다운로드 모드에 진입하세요."
            ),
            wraplength=590,
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
        ttk.Label(frame, textvariable=self.status_var, wraplength=590).pack(anchor="w")

        ttk.Separator(frame).pack(fill="x", pady=18)
        ttk.Label(
            frame,
            text=(
                "설치 완료 뒤 다운로드 모드용 BOOT/IO0 연결을 사용했다면 해제하고 Reset/전원을 다시 넣으세요. "
                "정상 부팅되면 휴대폰에서 ESP32_CAM_RC가 검색됩니다."
            ),
            wraplength=590,
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

        bootloader, partitions, boot_app0, firmware = firmware_paths()
        missing = [p.name for p in (bootloader, partitions, boot_app0, firmware) if not p.exists()]
        if missing:
            messagebox.showerror("Firmware 누락", "bundle 폴더에 다음 파일이 없습니다: " + ", ".join(missing))
            return

        self.flash_button.config(state="disabled")
        self.progress.start(12)
        self.status_var.set("ESP32 연결 및 Arduino-compatible Flash 준비 중…")
        threading.Thread(target=self._flash_worker, args=(port,), daemon=True).start()

    def _flash_worker(self, port: str) -> None:
        bootloader, partitions, boot_app0, firmware = firmware_paths()
        try:
            if self.erase_var.get():
                self._set_status("Flash 전체 삭제 중…")
                esptool.main(["--chip", "esp32", "--port", port, "erase-flash"])

            self._set_status("Firmware 설치 중… (Arduino 4-image layout)")
            esptool.main([
                "--chip", "esp32",
                "--port", port,
                "--baud", "460800",
                "--before", "default-reset",
                "--after", "hard-reset",
                "write-flash", "-z",
                "--flash-mode", "keep",
                "--flash-freq", "keep",
                "--flash-size", "keep",
                "0x1000", str(bootloader),
                "0x8000", str(partitions),
                "0xe000", str(boot_app0),
                "0x10000", str(firmware),
            ])
        except SystemExit as exc:
            code = exc.code if isinstance(exc.code, int) else 1
            if code != 0:
                self._finish(False, f"esptool 종료 코드 {code}. COM port와 보드의 다운로드 모드 상태를 확인하세요.")
                return
        except Exception as exc:  # noqa: BLE001
            self._finish(False, f"설치 실패: {exc}")
            return

        self._finish(
            True,
            "설치 완료. 다운로드 모드용 BOOT/IO0 연결을 사용했다면 해제한 뒤 Reset/전원을 다시 넣으세요. 정상 부팅되면 ESP32_CAM_RC가 검색됩니다.",
        )

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
