"""
示例 7：文件 / 媒体操作。

演示手机端文件读写与媒体控制（读取播放位置、seek、调音量）。
"""
from _common import make_phone


def main():
    phone = make_phone()

    # 1. 文件写入 / 读取 / 追加
    print("=== 文件读写 ===")
    path = "/sdcard/bridge/automation_test.txt"
    phone.file_write(path, "第一行\n")
    phone.file_append(path, "第二行\n")
    print("写入后内容：")
    print(phone.file_read(path))

    # 2. 文件存在性 / 列目录
    print("\n=== 文件列表 ===")
    print("文件存在：", phone.file_exists(path))
    for f in phone.file_list("/sdcard/bridge/")[:5]:
        print("  ", f)

    # 3. 复制 / 移动 / 删除
    print("\n=== 复制 & 删除 ===")
    phone.file_copy(path, path + ".bak")
    print("复制后存在：", phone.file_exists(path + ".bak"))
    phone.file_remove(path + ".bak")
    print("删除后存在：", phone.file_exists(path + ".bak"))

    # 4. 媒体控制（需手机当前有音乐播放）
    print("\n=== 媒体状态 ===")
    print("播放状态：", phone.media_state())
    print("播放位置：", phone.media_position())
    print("总时长：", phone.media_duration())
    phone.media_seek(10000)          # 跳到第 10 秒
    phone.media_volume(80)           # 音量调到 80
    print("已 seek 到 10s，音量 80")


if __name__ == "__main__":
    main()
