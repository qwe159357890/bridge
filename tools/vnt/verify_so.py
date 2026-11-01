#!/usr/bin/env python3
"""校验 VNT 官方预编译 libvnt_jni.so 是否可用于本项目。

用法:
    python tools/vnt/verify_so.py <path-to-so> [expected-sha256-file]

校验项:
  1. ELF64 / AArch64 机器类型
  2. PT_LOAD 段 16KB 页对齐（Android 15+ 要求）
  3. .dynstr / .dynsym 中存在 com.vnt 的 JNI 导出符号
  4. Java 层每个 native 声明都能找到对应导出符号（防止漏符号导致 UnsatisfiedLinkError）
  5. （可选）与官方 .sha256 文件比对

纯 Python 标准库实现，不依赖 NDK 的 llvm-nm / llvm-readelf，
因此本机无需安装任何工具链即可完成产物校验。
"""

import hashlib
import re
import struct
import sys
from pathlib import Path

# Java 绑定目录的候选位置：先在 app/ 下找（实现后），再回退到 references/ 源码
JAVA_BINDING_CANDIDATES = [
    Path("app/src/main/java/com/vnt"),
    Path("references/vnt/vnt-main/vnt-jni/java_example/com/vnt"),
]

REQUIRED_SYMBOLS = [
    "Java_com_vnt_VntManager_nativeInit",
    "Java_com_vnt_VntNetwork_nativeNextEvent",
    "Java_com_vnt_VntNetwork_nativeApplyRuntimeChangeFd",
]
PAGE_16K = 16384


def parse_elf(data: bytes):
    if data[:4] != b"\x7fELF":
        raise ValueError("不是 ELF 文件")
    if data[4] != 2:
        raise ValueError("不是 ELF64")
    if data[5] != 1:
        raise ValueError("不是小端序")
    e_machine = struct.unpack_from("<H", data, 18)[0]
    if e_machine != 183:  # EM_AARCH64
        raise ValueError(f"机器类型不是 AArch64 (e_machine={e_machine})")

    e_phoff = struct.unpack_from("<Q", data, 32)[0]
    e_phentsize = struct.unpack_from("<H", data, 54)[0]
    e_phnum = struct.unpack_from("<H", data, 56)[0]
    e_shoff = struct.unpack_from("<Q", data, 40)[0]
    e_shentsize = struct.unpack_from("<H", data, 58)[0]
    e_shnum = struct.unpack_from("<H", data, 60)[0]
    e_shstrndx = struct.unpack_from("<H", data, 62)[0]

    # ---- program headers: PT_LOAD 对齐 ----
    loads = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, _p_flags = struct.unpack_from("<II", data, off)
        if p_type != 1:  # PT_LOAD
            continue
        p_offset, p_vaddr, _p_paddr, _p_filesz, _p_memsz, p_align = struct.unpack_from(
            "<QQQQQQ", data, off + 8
        )
        loads.append((p_offset, p_vaddr, p_align))

    # ---- section headers ----
    def sh(i):
        off = e_shoff + i * e_shentsize
        sh_name, sh_type, _flags, _addr, sh_offset, sh_size, sh_link, _info, _align, sh_entsize = (
            struct.unpack_from("<IIQQQQIIQQ", data, off)
        )
        return dict(
            name=sh_name, type=sh_type, offset=sh_offset, size=sh_size,
            link=sh_link, entsize=sh_entsize,
        )

    sections = [sh(i) for i in range(e_shnum)]
    shstr = sections[e_shstrndx]
    strtab_raw = data[shstr["offset"]: shstr["offset"] + shstr["size"]]

    def cstr(blob: bytes, start: int) -> str:
        end = blob.find(b"\x00", start)
        return blob[start:end].decode("utf-8", "replace")

    names = {i: cstr(strtab_raw, s["name"]) for i, s in enumerate(sections)}

    dynstr = None
    dynsym = None
    for i, s in enumerate(sections):
        if names.get(i) == ".dynstr":
            dynstr = s
        elif names.get(i) == ".dynsym":
            dynsym = s
    if dynstr is None or dynsym is None:
        raise ValueError("缺少 .dynstr / .dynsym（可能被过度 strip）")

    dstr = data[dynstr["offset"]: dynstr["offset"] + dynstr["size"]]
    exported = set()
    entsize = dynsym["entsize"] or 24
    for off in range(dynsym["offset"], dynsym["offset"] + dynsym["size"], entsize):
        st_name, _info, _other, st_shndx, _value, _size = struct.unpack_from("<IBBHQQ", data, off)
        if st_shndx == 0:  # SHN_UNDEF，未定义/导入符号
            continue
        exported.add(cstr(dstr, st_name))

    return loads, exported


def native_declarations(java_dir: Path):
    """从 com/vnt/*.java 中抽出所有 native 方法名。"""
    names = []
    for f in sorted(java_dir.glob("*.java")):
        text = f.read_text(encoding="utf-8", errors="replace")
        names += re.findall(r"\bnative\s+[\w<>,\[\]\.]+\s+(\w+)\s*\(", text)
    return names


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    path = Path(sys.argv[1])
    data = path.read_bytes()
    print(f"文件      : {path}")
    print(f"大小      : {len(data):,} B")
    print(f"SHA256    : {hashlib.sha256(data).hexdigest()}")

    ok = True
    if len(sys.argv) >= 3:
        expected = Path(sys.argv[2]).read_text().strip().split()[0]
        match = hashlib.sha256(data).hexdigest().lower() == expected.lower()
        print(f"官方 SHA256: {expected}")
        print(f"sha256 校验: {'PASS' if match else 'FAIL'}")
        ok &= match

    try:
        loads, exported = parse_elf(data)
    except ValueError as e:
        print(f"ELF 解析失败: {e}")
        return 1

    print(f"ELF       : ELF64 / AArch64 / little-endian  PASS")
    print(f"PT_LOAD 段: {len(loads)} 个")
    for off, vaddr, align in loads:
        aligned = align >= PAGE_16K or (
            align > 0 and (vaddr - off) % PAGE_16K == 0
        )
        print(f"  off=0x{off:x} vaddr=0x{vaddr:x} align=0x{align:x} "
              f"16KB对齐={'PASS' if aligned else 'FAIL'}")
        ok &= aligned

    print("JNI 导出符号:")
    for sym in REQUIRED_SYMBOLS:
        present = sym in exported
        print(f"  {'PASS' if present else 'FAIL'}  {sym}")
        ok &= present

    java_vnt = sorted(s for s in exported if s.startswith("Java_com_vnt_"))
    print(f"com.vnt 导出符号总数: {len(java_vnt)}")

    # ---- Java 声明 vs so 导出，逐个对应 ----
    java_dir = next((p for p in JAVA_BINDING_CANDIDATES if p.is_dir()), None)
    if java_dir is None:
        print("Java 绑定目录未找到，跳过声明比对（可用 --java-dir 思路扩展）")
    else:
        decl = native_declarations(java_dir)
        print(f"Java native 声明: {len(decl)} 个（目录 {java_dir}）")
        missing = [d for d in decl if not any(s.endswith("_" + d) for s in java_vnt)]
        for d in missing:
            print(f"  FAIL  声明无对应导出符号: {d}")
        print(f"  声明/导出对应: {'PASS（零缺失）' if not missing else 'FAIL'}")
        ok &= not missing
        if len(decl) != len(java_vnt):
            print(f"  数量差异: 声明 {len(decl)} vs 导出 {len(java_vnt)}（逐个对应已校验，"
                  f"差异可能来自重载/JNI 动态注册，需人工确认）")
    print()
    print("结论:", "PASS —— 可用于 app/src/main/jniLibs/arm64-v8a/" if ok else "FAIL —— 不可使用")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
