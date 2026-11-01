# Agent 端发图双写补丁（Bridge v0.2.26 配套）

> 目标仓库：**真实 Agent** `F:\pythonWork\wxhelper\center-server\agent`（不是 `references/`，那是只读参考副本）
> 修改文件：`core/agent_client.py` → `_handle_upload_and_send()`
> 关联文档：`docs/dev/wechat-fix-ledger-v0.2.26.md` §3（客户端侧已撤回 2 秒折叠）
> 日期：2026-11-01

---

## §1 现象与结论

手机端（Bridge）发一张图，会话里出现**两条**自己发的图片；PC 客户端发一张图只有**一条**。

结论：**两端走的不是同一条路径**，PC 正常只证明 `send_image` 动作那条路径没问题。

| 路径 | 谁在用 | 发送前写 EXIF 去重标志 | 发送后持久化 | 结果 |
|---|---|---|---|---|
| `action == "send_image"`（`agent_client.py:1604`） | **PC 客户端** | ✅ `convert_to_jpeg_and_write_flag(image_path, generate_flag(msg_id))` | ✅ `_persist_sent_message(..., 3, "[图片消息]")` | 1 条 |
| `action == "upload_and_send"` → `_handle_upload_and_send`（`agent_client.py:2387`）+ 调用处 `2299` | **手机端（唯一可用路径）** | ❌ 原样 `send_image(save_path)` | ✅ `_persist_sent_message(..., 3, "[图片]")` | **2 条** |

第 2 条来自数据库监控补报：
`services/message_processor/processor_226.py:79` 对自发图片（`is_sender==1 and msg_type==3`）
**只认 EXIF 标志**才 `continue`（文本用 content 键 `is_self_sent_message`，视频用时间窗
`is_self_sent_video`）。`upload_and_send` 没写标志 ⇒ 监控认为这是「用户自己在微信里发的图」⇒
上报 ⇒ 与发送侧持久化的那条并存。

且 `_persist_sent_message` 只对 `msg_type == 1`（文本）调 `record_self_sent_message`、
`msg_type == 43`（视频）调 `record_self_sent_video`，**图片(3) 什么都不记录**，所以也没有第二重兜底。

---

## §2 改前原文（逐字留档 · `core/agent_client.py` 第 2446–2459 行）

```python
            if actual_type == "image":
                resp = await instance.send_image(target_wxid, save_path)
            elif actual_type == "video":
                resp = await instance.send_file(target_wxid, save_path)
            else:
                resp = await instance.send_file(target_wxid, save_path)

            resp_code = resp.code if hasattr(
                resp, 'code') else resp.get("code", -1)
            resp_msg = resp.msg if hasattr(resp, 'msg') else resp.get(
                "result", resp.get("msg", ""))
            # logger.info(
            #     f"[{self.agent_id}] DLL发送响应: code={resp_code}, msg={resp_msg}, type(resp)={type(resp).__name__}")
            return {"code": resp_code, "msg": resp_msg, "save_path": save_path, "actual_type": actual_type}
```

对照（**不动**，仅作参考）—— `send_image` 动作的正确写法（第 1606–1612 行）：

```python
            now = time.time()
            msg_id = int(now * 1000000) + uuid.uuid4().int % 10000
            # 转换为JPEG并写入EXIF去重标志，数据库监控据此识别并跳过重复上报
            flag = generate_flag(msg_id)
            jpeg_path = convert_to_jpeg_and_write_flag(image_path, flag)
            resp = await instance.send_image(target_wxid, jpeg_path)
```

---

## §3 改后

见文末「§6 改后回填」。

- 提交：`F:\pythonWork\wxhelper\center-server` 仓库 master `d9cc2b0`
  `agent: upload_and_send 图片发送前写入 EXIF 去重标志（手机端发图双写修复）`
  （仅本地提交，未 push）
- **生效条件**：Agent 进程必须重启（Python 代码热更新不生效）。
  真实 Agent 进程：`F:\pythonWork\wxhelper\center-server\agent\.venv\Scripts\python.exe ./main.py`

---

## §4 未改动的地方（及为什么）

- `processor_226.py` 的图片分支**保持只认 EXIF**：PC 端同样依赖它且工作正常，
  再加「时间窗兜底」会把「快速连发两张不同图」误判成同一条，风险大于收益。
- `_persist_sent_message` 的图片分支**不加**去重记录：监控侧没有对应的检查入口，加了也无效。
- PC 客户端路径一行未动。

---

## §5 回滚配方

```bash
cd /f/pythonWork/wxhelper/center-server/agent
git checkout -- core/agent_client.py          # 仅回滚本文件（该文件其它改动会一起丢，改前确认 git diff）
# 或按 §2 原文手工还原 2446–2459 行
```

触发回滚的信号：手机端发图后**一条都没有**（说明 EXIF 标志被误判，监控把唯一那条也跳过了），
或图片发出后对方收到的是花屏/损坏图（JPEG 转换环节出问题）。

---

## §6 改后回填（2026-11-01 已实施）

文件：`core/agent_client.py` → `_handle_upload_and_send()`，原第 2446–2459 行替换为：

```python
            # 图片发送前写入 EXIF 去重标志（与 send_image 动作保持一致）。
            # 数据库监控（processor_226.on_db_rows）对自发图片只认 EXIF 标志才跳过补报；
            # 不写标志就会出现「发送侧 _persist_sent_message + 监控补报」两条记录。
            # 手机端只能走 upload_and_send（无 send_image 端点），故补丁打在这里。
            send_path = save_path
            if actual_type == "image":
                try:
                    flag_id = int(time.time() * 1000000) + uuid.uuid4().int % 10000
                    jpeg_path = convert_to_jpeg_and_write_flag(save_path, generate_flag(flag_id))
                    if jpeg_path and os.path.exists(jpeg_path):
                        # 非 JPEG 原图会另存为 *_exif.jpg，删掉不再需要的原文件避免残留
                        if os.path.abspath(jpeg_path) != os.path.abspath(save_path):
                            try:
                                os.remove(save_path)
                            except Exception:
                                pass
                        send_path = jpeg_path
                except Exception as e:
                    logger.warning(f"[{self.agent_id}] 写入图片EXIF去重标志失败，按原图发送（可能产生重复记录）: {e}")

            if actual_type == "image":
                resp = await instance.send_image(target_wxid, send_path)
            elif actual_type == "video":
                resp = await instance.send_file(target_wxid, send_path)
            else:
                resp = await instance.send_file(target_wxid, send_path)

            resp_code = resp.code if hasattr(
                resp, 'code') else resp.get("code", -1)
            resp_msg = resp.msg if hasattr(resp, 'msg') else resp.get(
                "result", resp.get("msg", ""))
            # logger.info(
            #     f"[{self.agent_id}] DLL发送响应: code={resp_code}, msg={resp_msg}, type(resp)={type(resp).__name__}")
            return {"code": resp_code, "msg": resp_msg, "save_path": send_path, "actual_type": actual_type}
```

要点：

1. 只动 `actual_type == "image"` 分支，video / file 分支行为不变（`send_path` 仍等于 `save_path`）。
2. 转换失败（例如把非图片误判成 image）**降级为原样发送**，不会比改之前更差，只打一条 warning。
3. 返回的 `save_path` 变成带标志的 JPEG 路径 ⇒ 调用处（第 2276 行起）上传 MinIO、
   生成缩略图、`os.remove(save_path)` 用的都是这个文件，临时目录不留孤儿文件；
   非 JPEG 原图在转换后即被删除（与 `send_image` 动作第 1632–1634 行同款处理）。
4. 依赖 `utils.exif_flag` 的 `generate_flag` / `convert_to_jpeg_and_write_flag`，
   该文件第 17 行已导入（`send_image` 一直在用），`piexif` / `Pillow` 在运行环境中已存在。
5. `python -m py_compile core/agent_client.py` 通过。
