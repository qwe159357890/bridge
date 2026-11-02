#!/usr/bin/env python3
"""校验 VNT 官方预编译 libvnt_jni.so（v0.2.32 起：1.x / VntApp v1.2.9，2 套 ABI）。

用法:
    python tools/vnt/verify_so.py <path-to-so> [expected-sha256-file] [--abi <abi>]

ABI 依次从 --abi、路径中的 ``jniLibs/<abi>/``、文件名 ``vnt-jni-<abi>-`` 推断。

校验项:
  1. ELF 头（class/data/machine）与目标 ABI 匹配
     - arm64-v8a : ELF64 / EM_AARCH64 (183)
     - x86_64    : ELF64 / EM_X86_64 (62)
  2. PT_LOAD 段页对齐：**1.x 官方产物是 4KB（0x1000）对齐**。
     ⚠️ 这不是 Android 15+ 要求的 16KB 页对齐；官方 1.x 已停更且无 16KB 重编产物，
     在 16KB page size 设备上存在加载失败风险，属已知限制，脚本按 4KB 判定并 WARN。
  3. .dynstr / .dynsym 中存在 top.wherewego.vnt.jni 的 JNI 导出符号（6 个）
  4. Java 层每个 native 声明都能找到对应导出符号（防止漏符号导致 UnsatisfiedLinkError）
  5. （可选）与官方 .sha256 文件比对

纯 Python 标准库实现，不依赖 NDK 的 llvm-nm / llvm-readelf，
因此本机无需安装任何工具链即可完成产物校验。

历史：v0.2.30~v0.2.31 校验的是 2.x / com.vnt.*（3 套 ABI、16KB 对齐、23 个符号）；
v0.2.32 起因「存量服务端与客户端全为 1.x、必须互通」改为 1.x / top.wherewego.vnt.jni.*。
"""

import hashlib
import re
import struct
import sys
from pathlib import Path

# Java 绑定目录（v0.2.32：官方 VntApp v1.2.9 的 14 个类，包名不可改）
JAVA_BINDING_CANDIDATES = [
    Path("app/src/main/java/top/wherewego/vnt/jni"),
]

# 目标 ABI -> (ELF class, e_machine, 页对齐要求, 描述)
# 1.x 官方产物 2023 年前编译，PT_LOAD p_align 均为 0x1000（4KB），不是 16KB。
ABI_SPEC = {
    "arm64-v8a": (2, 183, 4096, "ELF64 / AArch64 / little-endian / 4KB"),
    "x86_64": (2, 62, 4096, "ELF64 / x86-64 / little-endian / 4KB"),
}

REQUIRED_SYMBOLS = [
    "Java_top_wherewego_vnt_jni_Vnt_new0",
    "Java_top_wherewego_vnt_jni_Vnt_stop0",
    "Java_top_wherewego_vnt_jni_Vnt_wait0",
    "Java_top_wherewego_vnt_jni_Vnt_waitTimeout0",
    "Java_top_wherewego_vnt_jni_Vnt_list0",
    "Java_top_wherewego_vnt_jni_Vnt_drop0",
]

JNI_PREFIX = "Java_top_wherewego_vnt_jni_"


def guess_abi(path: Path, explicit: str | None) -> str:
    if explicit:
        if explicit not in ABI_SPEC:
            raise ValueError(f"未知 ABI {explicit!r}，可选：{', '.join(ABI_SPEC)}")
        return explicit
    parts = {p.lower() for p in path.parts}
    for abi in ABI_SPEC:
        if abi in parts:
            return abi
    name = path.name.lower()
    for abi in ABI_SPEC:
        if abi in name:
            return abi
    raise ValueError("无法从路径推断 ABI，请用 --abi 显式指定")


def parse_elf(data: bytes, elf_class: int, machine: int, page: int):
    """解析 ELF，返回 (PT_LOAD 列表, 导出符号集合, page)。兼容 ELF32/ELF64。"""
    if data[:4] != b"\x7fELF":
        raise ValueError("不是 ELF 文件")
    if data[4] != elf_class:
        raise ValueError(f"ELF class={data[4]}，期望 {elf_class}")
    if data[5] != 1:
        raise ValueError("不是小端序")
    e_machine = struct.unpack_from("<H", data, 18)[0]
    if e_machine != machine:
        raise ValueError(f"机器类型 e_machine={e_machine}，期望 {machine}")

    is64 = elf_class == 2
    if is64:
        e_phoff = struct.unpack_from("<Q", data, 32)[0]
        e_shoff = struct.unpack_from("<Q", data, 40)[0]
        e_phentsize, e_phnum = struct.unpack_from("<HH", data, 54)
        e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 58)
        ph_fmt, ph_align_off = "<IIQQQQQQ", 48
        sh_fmt = "<IIQQQQIIQQ"
        sym_fmt, sym_size, sym_shndx_idx = "<IBBHQQ", 24, 3
    else:
        e_phoff = struct.unpack_from("<I", data, 28)[0]
        e_shoff = struct.unpack_from("<I", data, 32)[0]
        e_phentsize, e_phnum = struct.unpack_from("<HH", data, 42)
        e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 46)
        ph_fmt, ph_align_off = "<IIIIIIII", 28
        sh_fmt = "<IIIIIIIIII"
        sym_fmt, sym_size, sym_shndx_idx = "<IIIBBH", 16, 5

    loads = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        fields = struct.unpack_from(ph_fmt, data, off)
        if fields[0] != 1:  # PT_LOAD
            continue
        p_offset = fields[2] if is64 else fields[1]
        p_vaddr = fields[3] if is64 else fields[2]
        p_align = fields[7]
        loads.append((p_offset, p_vaddr, p_align))

    def sh(i):
        off = e_shoff + i * e_shentsize
        f = struct.unpack_from(sh_fmt, data, off)
        return dict(name=f[0], type=f[1], offset=f[4], size=f[5], link=f[6], entsize=f[9])

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
    entsize = dynsym["entsize"] or sym_size
    for off in range(dynsym["offset"], dynsym["offset"] + dynsym["size"], entsize):
        f = struct.unpack_from(sym_fmt, data, off)
        st_name, st_shndx = f[0], f[sym_shndx_idx]
        if st_shndx == 0:  # SHN_UNDEF
            continue
        exported.add(cstr(dstr, st_name))

    return loads, exported, page


def native_declarations(java_dir: Path):
    """从 top/wherewego/vnt/jni/**/*.java 中抽出所有 native 方法名。"""
    names = []
    for f in sorted(java_dir.rglob("*.java")):
        text = f.read_text(encoding="utf-8", errors="replace")
        names += re.findall(r"\bnative\s+[\w<>,\[\]\.]+\s+(\w+)\s*\(", text)
    return names


def main() -> int:
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2
    explicit_abi = None
    if "--abi" in args:
        i = args.index("--abi")
        if i + 1 >= len(args):
            print("--abi 缺少参数")
            return 2
        explicit_abi = args[i + 1]
        del args[i:i + 2]
    path = Path(args[0])
    data = path.read_bytes()
    try:
        abi = guess_abi(path, explicit_abi)
    except ValueError as e:
        print(f"ABI 判定失败: {e}")
        return 1
    elf_class, machine, page, label = ABI_SPEC[abi]

    print(f"文件      : {path}")
    print(f"目标 ABI  : {abi} ({label})")
    print(f"大小      : {len(data):,} B")
    print(f"SHA256    : {hashlib.sha256(data).hexdigest()}")

    ok = True
    if len(args) >= 2:
        expected = Path(args[1]).read_text().strip().split()[0]
        match = hashlib.sha256(data).hexdigest().lower() == expected.lower()
        print(f"官方 SHA256: {expected}")
        print(f"sha256 校验: {'PASS' if match else 'FAIL'}")
        ok &= match

    try:
        loads, exported, page = parse_elf(data, elf_class, machine, page)
    except ValueError as e:
        print(f"ELF 解析失败: {e}")
        return 1

    print(f"ELF       : {label}  PASS")
    print(f"PT_LOAD 段: {len(loads)} 个（本版要求 {page // 1024}KB 对齐）")
    for off, vaddr, align in loads:
        aligned = align >= page or (align > 0 and (vaddr - off) % page == 0)
        print(f"  off=0x{off:x} vaddr=0x{vaddr:x} align=0x{align:x} "
              f"{page // 1024}KB对齐={'PASS' if aligned else 'FAIL'}")
        ok &= aligned
    if align < 16384:
        print("  ⚠️ WARN: 1.x 官方 so 仅 4KB 对齐，非 Android 15+ 要求的 16KB 页对齐；"
              "在 16KB page size 设备上可能加载失败（官方已停更，无重编产物）。")

    print("JNI 导出符号:")
    for sym in REQUIRED_SYMBOLS:
        present = sym in exported
        print(f"  {'PASS' if present else 'FAIL'}  {sym}")
        ok &= present

    java_vnt = sorted(s for s in exported if s.startswith(JNI_PREFIX))
    print(f"{JNI_PREFIX}* 导出符号总数: {len(java_vnt)}")

    java_dir = next((p for p in JAVA_BINDING_CANDIDATES if p.is_dir()), None)
    if java_dir is None:
        print("Java 绑定目录未找到，跳过声明比对")
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
    print("结论:", f"PASS —— 可用于 app/src/main/jniLibs/{abi}/" if ok else "FAIL —— 不可使用")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
