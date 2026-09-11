# edap-s3 Disk MultipartStore 设计(Phase 3)

> 本文档是 `edap-s3-parent/edap-s3-core` MultipartStore 磁盘版后端的设计规范。
> 覆盖磁盘布局、关键路径实现、并发与一致性、进程崩溃恢复、性能边界与踩坑预案。
>
> **目标读者**:在 `edap-s3-parent` 下做 MultipartStore 落盘 / 集成 `DiskObjectStore` 的开发者;以及需要把 edap-s3 推到 GB 级上传 / 多实例部署场景的应用开发者。
>
> **覆盖范围**:
>
> | 层 | 本文档章节 | 对应类 | 关系 |
> |----|-----------|--------|------|
> | SPI | 第三章 | `io.edap.s3.store.MultipartStore`(沿用) | 接口零改动 |
> | 磁盘实现 | 第四~七章 | `io.edap.s3.store.disk.DiskMultipartStore`(新增) | 与 `InMemoryMultipartStore` 平级 |
> | 磁盘 ObjectStore | 第八章 | `io.edap.s3.store.disk.DiskObjectStore`(新增) | MultipartStore.complete() 调 put |
> | 启动恢复 | 第九章 | `DiskMultipartStore.startupRecovery()` | 扫盘 + 孤儿清理 |
> | 集成测试 | 第十章 | `DiskMultipartStoreIT` | 真实盘 + 真实 ObjectStore |

---

## 一、目标与范围

### 1.1 解决什么问题

Phase 2 的 `InMemoryMultipartStore` 跟 `InMemoryObjectStore` 同风格,所有 part bytes 堆内(`new byte[(int) contentLength]`)。问题:

- **OOM 风险**:单 part 最大 5GB(S3 上限),上传 5GB part 必 OOM;uploadPart 调用栈里至少两份 byte[] 拷贝(读 buf → 总 buffer),峰值 ×2。
- **complete 整体加载**:InMemoryMultipartStore.complete() 算 `new byte[totalSizeInt]` 再 `arraycopy` 拼接,5GB upload 直接爆。
- **重启即丢**:uploads / parts 全在 ConcurrentHashMap,进程崩了所有进行中的 multipart 状态消失,客户端 ListMultipartUploads 看到的全部凭空蒸发。
- **多实例不友好**:状态在 JVM 内,横向扩展时按 uploadId hash 路由到固定节点,扩容缩容要把数据搬走。

**目标**:把 MultipartStore 落到磁盘,使:

1. 单 upload / 单 part 体量上 GB 不 OOM(流式 I/O)
2. 进程重启后进行中的 upload 仍可 ListParts / CompleteMultipart / AbortMultipart
3. 多实例部署场景下,upload 状态可被任意节点访问(前提:共享文件系统 / 共享块设备)
4. 错误码 / 行为完全对齐 InMemory 版,handler / IT 零改动

### 1.2 不做什么

- **不做 DB 化元数据**:Phase 3 元数据走 JSON 文件 + 路径约定,SQLite/RocksDB 留 Phase 4。
- **不做分布式锁**:Phase 3 假设单进程 / 共享文件系统,文件锁即可;跨主机的强一致(etcd/ZK) 留 Phase 4。
- **不做 multipart upload 跨节点迁移**:Phase 3 多实例只读共享盘,不做 active-active;upload 节点由客户端(或前置 LB 按 uploadId hash)决定。
- **不做 part 压缩 / 加密**:S3 标准 multipart upload 也不压缩,客户端自己负责。
- **不动 SPI**: `MultipartStore` / `MultipartUpload` / `MultipartPart` 接口零改动,handler 零改动。

---

## 二、设计决策

| # | 决策 | 备选 | 理由 |
|---|------|------|------|
| 1 | **元数据走 JSON 文件** | SQLite / RocksDB / 内存 | 无新依赖;git-friendly;调试可 `cat` 看;Phase 3 单进程单盘,JSON 写冲突极低 |
| 2 | **per-uploadId 内存锁** | 文件锁 / fcntl | 文件锁跨平台坑多;内存锁够用;崩溃后锁随进程死,等于放锁 |
| 3 | **complete 用 `meta.json` → `meta.json.completing` 原子 rename 占所有权** | read-modify-write | rename 在同一文件系统是原子的( POSIX `rename(2)` );占所有权后其他 complete / uploadPart 看到 `.completing` 直接 `NO_SUCH_UPLOAD`,跟 InMemory `uploads.remove` 语义对齐 |
| 4 | **part bytes 写 `.part.tmp` + atomic rename** | 直接写 `.part` | 写崩溃可能半截 → 客户端重传看到错位字节;tmp + rename 保证要么旧版要么新版 |
| 5 | **流式 I/O,不堆内聚** | `byte[]` 全量 | 5GB part 进堆直接 OOM;流式 + DigestInputStream 边读边算 MD5 |
| 6 | **abort = 删整目录** | 单 part 删 | part 数量大时删多次;整目录 `Files.walk + delete` 一次搞定 |
| 7 | **启动扫盘,按 key 反查 ObjectStore 决定孤儿** | TTL 自动清 | 上传可能因网络问题中断挂在内存外等用户重传,TTL 太短误杀 |
| 8 | **`S3ServerBuilder.multipartStore(...)` 用同一 SPI** | 加 `diskRoot()` 配置 | SPI 已经在 builder 上;磁盘版只是另一个 impl,builder 不需要改 |

---

## 三、磁盘布局

### 3.1 路径约定

```
<root>/
├── <bucket>/
│   ├── .multipart/                       # 桶内 multipart 临时区(S3 用 .minio.sys 风格,藏起来)
│   │   ├── <uploadId-A>/
│   │   │   ├── meta.json                 # upload 元数据
│   │   │   ├── 00001.part                # part 1 bytes
│   │   │   ├── 00002.part
│   │   │   └── 00003.part
│   │   └── <uploadId-B>/
│   │       ├── meta.json
│   │       └── 00001.part
│   └── <key>                              # 普通对象(ObjectStore 管)
```

路径示例:`/data/s3/bucket1/.multipart/8b1f...e0/meta.json`

### 3.2 文件命名细节

| 文件 | 命名规则 | 大小 |
|------|---------|------|
| `meta.json` | 字面 `meta.json` | 通常 < 1KB(part 多时 < 10KB) |
| `<partNumber>.part` | 5 位十进制 + `.part` 后缀 | part bytes 原样大小(可达 5GB) |
| `<partNumber>.tmp` | 5 位十进制 + `.tmp` 后缀(临时) | 写一半时存在,rename 后消失 |
| `meta.json.completing` | `meta.json` + `.completing`(标记) | 0 字节或占位 JSON |

partNumber 用 5 位补零(`%05d`)便于按字典序遍历时 partNumber 自然升序。

### 3.3 `meta.json` 格式

```json
{
  "uploadId":  "8b1f2e30-...-e0",
  "bucket":    "bucket1",
  "key":       "uploads/2026/photo.jpg",
  "contentType": "image/jpeg",
  "initiated": "2026-09-11T10:23:45Z",
  "userMetadata": {"x-amz-meta-foo": "bar"},
  "parts": {
    "1": {"etag": "5d41402abc4b2a76b9719d911017c592", "size": 11, "lastModified": "..."},
    "2": {"etag": "6dcd4ce23d88e2ee9568ba546c007c63d9131c1b", "size": 7,  "lastModified": "..."}
  }
}
```

- `parts` 字段:client 上传过的 part 元数据,key = partNumber 字符串,value 不含 body(body 在 `.part` 文件)
- 内存里 InMemoryMultipartStore 用 `ConcurrentSkipListMap<Integer, MultipartPart>`;磁盘版只持久化 `etag / size / lastModified`,body 走 file
- `lastModified` 给 ListParts 排序 / 展示用

---

## 四、关键路径实现

### 4.1 `initiate(bucket, key, contentType, userMetadata)`

```
1. requireBucket(bucket)
2. uploadId = UUID.randomUUID().toString()
3. dir = <root>/<bucket>/.multipart/<uploadId>/
4. Files.createDirectories(dir)
5. meta = {uploadId, bucket, key, contentType, initiated=now, userMetadata, parts={}}
6. writeAtomic(dir + "/meta.json", toJson(meta))
   // 写崩溃恢复:启动扫盘时,meta.json 不存在的 upload 目录视为垃圾,直接删
7. return uploadId
```

**要点**:
- `writeAtomic` = `Files.write(tmp); Files.move(tmp, dst, ATOMIC_MOVE)`;写一半崩了 tmp 残留,启动扫盘时清
- `dir` 不存在 → `mkdir`;存在(重 UUID)→ 概率极低,抛 500

### 4.2 `uploadPart(uploadId, partNumber, body, contentLength)`

```
1. 参数校验:partNumber ∈ [1, 10000], contentLength ≥ 0
2. upload = lockAndLoad(uploadId)
   // 没拿到锁或 meta.json 不存在 → NO_SUCH_UPLOAD
   // 拿到 .completing 标记 → NO_SUCH_UPLOAD(complete 在进行中)
3. partTmp = <dir>/<%05d>.tmp
4. partFinal = <dir>/<%05d>.part
5. DigestInputStream dis = new DigestInputStream(body, MD5)
   // 边读边算 MD5,边写到 tmp
   Files.copy(dis, partTmp) // 用 InputStream.transferTo 或 buffered loop
6. etag = toHex(dis.getMessageDigest().digest())
7. atomicRename(partTmp, partFinal)
   // rename 失败(目标已存在)→ unlink 后再 rename,保证 last-write-wins
8. 更新 upload.parts[partNumber] = {etag, size=contentLength, lastModified=now}
   writeAtomic(dir + "/meta.json", toJson(meta))
   // meta 写失败:part 文件已落,下次启动扫盘时 meta 缺失 part 信息,会清理 part 文件(降级)
9. return etag
```

**并发**:同 partNumber 并发 uploadPart → 临时文件 rename 互斥(OS 保证),meta 更新序列化(同一 JVM 单线程竞争;多 JVM 在 Phase 3 不支持,见 §7)

**contentLength 校验**:客户端声明的 Content-Length 跟实际读到的字节数不一致时,以实际读到的为准,S3 规范允许(不抛错)。Phase 2 InMemory 行为:`total < data.length` 跳出循环 → 截断;Phase 3 同样语义。

### 4.3 `complete(uploadId, parts)`

```
1. upload = lockAndLoad(uploadId)
2. (a) meta.json → meta.json.completing 原子 rename
   // 占所有权:后续所有 uploadPart / abort / listParts 看到 .completing → NO_SUCH_UPLOAD
   // rename 失败(被别人占走)→ NO_SUCH_UPLOAD
3. (b) 校验 parts:乱序 sort by partNumber;每 part:
   - 必须已上传(parts 中存在)
   - etag 必须匹配
   - 任一失败 → 抛 INVALID_ARGUMENT,删 .completing 还原 meta.json
4. (c) 拼装并 put 到 ObjectStore:
   - MD5:拼接各 part 的 raw MD5 bytes,MD5(concat) → 复合 ETag 前缀
   - body:用 SequenceInputStream 把各 part 的 FileInputStream 串起来
   - PutStream = (SequenceInputStream, contentType, userMetadata, totalSize, null)
   - objectStore.put(bucket, key, PutStream)
5. (d) 删 dir(包括 meta.json.completing)
   // 删失败不致命:启动扫盘时 ObjectStore 已有 key → 视为孤儿删
6. (e) 释放锁
7. return new CompletedObject(bucket, key, finalEtag, totalSize)
```

**为什么用 `SequenceInputStream`**:避免在堆上拼 5GB byte[]。`objectStore.put` 必须支持流式 InputStream(已经支持,因为它是 `PutStream.content()`,Phase 1 内存版也用 `ByteArrayInputStream`,接口契约就是流)。

**ObjectStore 自身的兼容性**:DiskObjectStore(§8)接收 SequenceInputStream,内部用 buffered loop + DigestInputStream 边读边算 MD5 + 边写磁盘。MD5 计算两次 — multipart 层算复合 ETag,ObjectStore 层算最终 ETag — 不冲突。

### 4.4 `abort(uploadId)`

```
1. dir = <root>/<bucket>/.multipart/<uploadId>/
   // 上传完 + complete 失败的回滚场景:dir 已被删;直接 NO_SUCH_UPLOAD
2. Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
3. return
```

并发:跟 complete 同时进行 → complete 拿到 dir 后(步骤 4.3.2 原子 rename),abort 的 walkdir 失败 → 等于 NO_SUCH_UPLOAD。客户端收到 404,符合 S3 规范。

### 4.5 `listUploads(bucket)`

```
1. requireBucket(bucket)
2. baseDir = <root>/<bucket>/.multipart/
3. List<MultipartUpload> result = []
4. for (Path d : Files.list(baseDir)):
     meta = parseJson(d + "/meta.json")
     if meta == null: continue   // 启动扫盘还没清的孤儿,跳过
     result.add(toMultipartUpload(meta))
5. 按 initiated 升序 sort
6. return result
```

**性能**:桶内 upload 多时(>1k)每次 list 都扫盘 + parse JSON,慢。Phase 3 不优化,Phase 4 可加内存缓存 + 文件 mtime 失效。

### 4.6 `listParts(uploadId)`

```
1. upload = loadMeta(uploadId)
2. List<MultipartPart> result = []
3. for entry in upload.parts:
     result.add(new MultipartPart(partNumber, etag, size, lastModified, body=null))
   // body=null:listParts 不返回 body(InMemory 版同样 body 不返回;见 InMemoryMultipartStoreTest 注释)
4. 按 partNumber 升序 sort
5. return result
```

---

## 五、并发与一致性

### 5.1 内存锁

```java
private final ConcurrentMap<String, ReentrantLock> uploadLocks = new ConcurrentHashMap<>();

private ReentrantLock lockFor(String uploadId) {
    return uploadLocks.computeIfAbsent(uploadId, k -> new ReentrantLock());
}
```

- acquire 失败 → 抛 `NO_SUCH_UPLOAD`(跟 InMemory 对齐)
- uploadId 长期不活跃 → Phase 4 加 `removeIf(no recent activity)`,Phase 3 不清,内存随 heap 涨(单桶活跃 upload < 1k 时可忽略)

### 5.2 关键路径锁定

| 路径 | 持锁范围 | 锁外还有什么 |
|------|---------|------------|
| initiate | 仅 meta.json 写(无共享状态) | 无 |
| uploadPart | 整个 upload(其他 partNumber 也阻塞,简化并发模型) | 无 |
| complete | 整个 upload + 原子 rename + put + 删目录 | `objectStore.put` 可能慢(GB 级),持锁期长 |
| abort | 整个 upload | 同 complete |
| listUploads | 不持锁 | `Files.list` 拿 snapshot,迭代期目录变动不感知(下一调用可见) |
| listParts | 不持锁(只读) | 同上 |

**持锁期长风险**:`complete` 持锁期 = 磁盘读 + put ObjectStore + 删目录。GB 级 part 可能几十秒。其他 uploadPart 被阻塞。客户端行为:重试 / 走别的节点(Phase 3 单进程 OK,Phase 4 多实例 + 客户端 hash 路由更友好)。

### 5.3 崩溃后的一致性

| 崩溃点 | 残留 | 启动恢复(§9) |
|--------|------|--------------|
| initiate 写 meta.json 前 | 无目录 | 无事 |
| initiate 写 meta.json 后 | dir + meta.json | OK,正常 active upload |
| uploadPart 写 tmp 前 | 无事 | 无事 |
| uploadPart 写 tmp 后 rename 前 | `<partNumber>.tmp` | 启动扫盘清 tmp |
| uploadPart rename 后写 meta.json 前 | `.part` 但 meta 没记录 | 启动扫盘看到 `.part` 但 meta.parts 没此 partNumber → 删除 |
| uploadPart 写 meta.json 后 | 完整 | OK |
| complete rename meta.json → .completing 前 | meta.json 完整 | OK |
| complete rename meta.json → .completing 后 put 前 | meta.json 不见,目录里只有 .completing | 启动扫盘看到 `.completing` → 视为进行中 complete(等待下个客户端重试);也可选直接恢复 meta.json 重新尝试 |
| complete put ObjectStore 后删目录前 | ObjectStore 有 key + 孤儿目录 | 启动扫盘反查 ObjectStore 已有 key → 删孤儿目录 |
| abort walkdir 中途 | 部分 part 残留 | 启动扫盘清 |

---

## 六、`S3Exception` 错误码映射

完全沿用 InMemory 版,handler 不感知:

| 场景 | S3ErrorCode | HTTP |
|------|------------|------|
| 桶不存在 | `NO_SUCH_BUCKET` | 404 |
| partNumber 越界 | `INVALID_ARGUMENT` | 400 |
| contentLength < 0 | `INVALID_ARGUMENT` | 400 |
| uploadId 不存在 | `NO_SUCH_UPLOAD` | 404 |
| complete 时 part 未上传 | `INVALID_ARGUMENT` | 400 |
| complete 时 etag 不匹配 | `INVALID_ARGUMENT` | 400 |
| 磁盘 I/O 失败 | `INTERNAL_ERROR` | 500 |

---

## 七、多实例部署(Phase 3 简化)

Phase 3 **不实现** 跨进程强一致。假设:

- 所有实例挂同一 NFS / GlusterFS / 共享块设备
- 客户端按 uploadId hash 路由到固定节点(由前置 LB / SDK 实现)
- 节点 A 上传 part 1,客户端再发 part 2 到节点 B → 节点 B 通过共享盘看到 part 1 + meta,继续

**冲突场景**:同一 uploadId 被同时路由到 A 和 B(客户端不 hash 或 hash 错误),A 和 B 都 uploadPart 同 partNumber:

- POSIX `rename` 在共享文件系统上互斥( NFSv4 + proper locking ),保证不交错写
- meta.json 写:两个 node 都写一份 JSON → **last-writer-wins,可能丢 part 元数据**;Phase 3 接受,客户端不应跨节点
- Phase 4 可加分布式锁(etcd / Redis)或强制 uploadId → 固定节点

**显式不约束**:文档写明 "Phase 3 假设单进程 / 客户端按 uploadId 路由到固定节点",与 InMemory 同部署模型。

---

## 八、DiskObjectStore 同步落地

MultipartStore.complete() 调 `objectStore.put(...)`,磁盘版必须配磁盘版 ObjectStore(内存版 GB 级 put 直接 OOM)。`DiskObjectStore` 设计要点:

### 8.1 路径

```
<root>/<bucket>/<key>
```

例:`/data/s3/bucket1/uploads/2026/photo.jpg`

### 8.2 `put(bucket, key, PutStream body)`

```
1. requireBucket(bucket)
2. 目标 = <root>/<bucket>/<key>
3. tmp = 目标 + ".tmp." + uuid
4. DigestInputStream dis = new DigestInputStream(body.content(), MD5)
5. Files.copy(dis, tmp, REPLACE_EXISTING)
6. size = Files.size(tmp)
7. etag = toHex(dis.getMessageDigest().digest())
8. meta = new ObjectMeta(key, size, etag, body.contentType(), now, body.userMetadata())
9. writeAtomic(<dir>/<key>.meta.json, toJson(meta))
10. atomicRename(tmp, 目标)
11. 返回 meta
```

multipart 拼装场景(`SequenceInputStream` 整个对象可达 5GB):Files.copy 会把整流读出来;buffer 8KB 边读边算 MD5,内存常驻 ~8KB,无 OOM。

### 8.3 `get / head / delete / list / isEmpty / close`

按路径约定实现,这里不展开;与 InMemory 行为对齐即可(handler 拿到的 S3Exception 错误码相同)。

---

## 九、启动恢复

### 9.1 触发时机

`DiskMultipartStore` 构造时跑一次(同步,启动期),跑完才让 S3ServerBuilder 接受请求。

### 9.2 扫盘算法

```
def startupRecovery(root):
    for bucketDir in Files.list(root):           # 每个桶
        multipartDir = bucketDir + "/.multipart"
        if not exists(multipartDir): continue
        for uploadDir in Files.list(multipartDir):
            dirName = uploadDir.getFileName().toString()
            
            # 1. 孤儿 tmp(写崩溃残留)
            for tmp in Files.list(uploadDir):
                if tmp.name.endsWith(".tmp"):
                    Files.delete(tmp)
            
            # 2. meta.json 不存在 → 整个目录是垃圾(可能只有 .part 文件或 .completing)
            metaFile = uploadDir + "/meta.json"
            if not exists(metaFile):
                if exists(uploadDir + "/meta.json.completing"):
                    # complete 进行中崩了,尝试恢复 meta.json 重新 complete
                    restoreMetaFromCompleting(uploadDir)
                else:
                    # 纯孤儿,直接删
                    deleteRecursive(uploadDir)
                continue
            
            # 3. meta.json 存在,但 parts 里没记录的 .part 文件 → 孤儿 part
            meta = parseJson(metaFile)
            for f in Files.list(uploadDir):
                if f.name matches "<%05d>.part":
                    partNum = parsePartNumber(f.name)
                    if partNum not in meta.parts:
                        Files.delete(f)   # meta 没记录的 part 是垃圾
            
            # 4. 反查 ObjectStore:key 已存在 → 完成过了但目录没清掉 → 删孤儿目录
            objMeta = objectStore.head(bucket, meta.key)
            if objMeta != null:
                deleteRecursive(uploadDir)
            
            # 5. meta.json.completing 残留 + ObjectStore 没 key → 上次 complete 崩了
            #    恢复 meta.json,客户端下次 CompleteMultipart 重试时拿所有权继续
            if exists(uploadDir + "/meta.json.completing"):
                Files.move(uploadDir + "/meta.json.completing", metaFile)
```

**性能**:启动期一次性扫,桶多 + upload 多时可能秒级;Phase 3 接受。Phase 4 可加并行扫 + 增量恢复。

### 9.3 失败处理

- 某个 upload 目录恢复失败(IO 异常 / JSON parse 失败)→ log.warn 跳过,不阻断启动;下次扫盘再试
- meta.json parse 失败 → 视为孤儿,删目录(丢这一条 upload 状态,客户端 listUploads 看不到,但已经在传的 part 不影响)

---

## 十、测试覆盖

### 10.1 单元测试 `DiskMultipartStoreTest`

(JUnit 5 + `@TempDir` 临时目录,每个 case 独立 root)

| 用例 | 验证点 |
|------|--------|
| `initiate_createsMetaJson` | dir + meta.json 存在;JSON 字段对齐 |
| `initiate_missingBucket` | NO_SUCH_BUCKET |
| `uploadPart_writesTmpAndRenames` | 写完只有 `.part`,无 `.tmp` |
| `uploadPart_outOfOrder_ok` | part 2 → part 1 → part 3,listParts 升序 |
| `uploadPart_samePartNumberLastWriteWins` | 并发 2 thread,final 以最后一个写为准 |
| `uploadPart_invalidPartNumber` | 0 / 10001 → INVALID_ARGUMENT |
| `complete_assemblesBytesAndCompositeEtag` | 跟 InMemory 的 `completeAssemblesBytesAndEtag` 等价,ETag 完全一致 |
| `complete_rejectsBadEtag` | INVALID_ARGUMENT |
| `complete_rejectsMissingPart` | INVALID_ARGUMENT |
| `complete_rejectsUnknownUpload` | NO_SUCH_UPLOAD |
| `abort_clearsDirectory` | abort 后 dir 不存在 |
| `abort_unknownUpload` | NO_SUCH_UPLOAD |
| `listUploads_filtersByBucket` | 多桶隔离 |
| `listParts_returnsAllUploadedParts` | 3 parts,升序,body=null |

### 10.2 集成测试 `DiskMultipartStoreIT`(真实 disk + 真实 DiskObjectStore)

| 用例 | 验证点 |
|------|--------|
| `e2e_uploadComplete` | initiate → uploadPart × 3 → complete → get 拿回完整对象,byte 一致 |
| `largePart_doesNotOom` | uploadPart 单 part 256MB(可用 `-Xmx256m` 验证,Phase 2 InMemory 必 OOM) |
| `processCrash_simulated` | initiate → uploadPart × 2 → 模拟崩溃(关 store 不 abort)→ 重启 store → 启动恢复 → listParts 看得到 → complete 成功 |
| `processCrash_afterCompleteBeforeDelete` | complete put 后删目录前崩 → 重启扫盘看到 ObjectStore 有 key,删孤儿目录 |
| `multipartAbortAfterCrash` | initiate → uploadPart → abort 中途崩 → 重启扫盘清掉残留 tmp |
| `concurrentCompleteAndUploadPart` | complete 跑起来后,uploadPart 拿到 NO_SUCH_UPLOAD(锁语义验证) |
| `diskUsageMeasured` | 跑一轮 100 parts × 5MB upload(用随机 bytes)→ du -sh root,验证 size 跟声明 sum 一致 |

### 10.3 与 InMemory 行为对齐测试 `DiskInMemoryEquivalenceTest`

跑同一组测试 fixture(随机 key / bytes / parts),断言两 impl 的:
- `complete` 返回的 `CompletedObject`(etag / size)完全一致
- `listParts` 返回的 etag / size / lastModified 一致(lastModified 可能差几毫秒,允许 ±1s)

目的:防止 Phase 3 走偏。

### 10.4 启动恢复专项测试 `DiskMultipartStoreRecoveryIT`

| 用例 | 模拟场景 |
|------|---------|
| `recovery_orphanTmpDeleted` | 手工创建 `<uploadId>/00001.part.tmp` + meta,启 store → tmp 被删 |
| `recovery_orphanPartDeleted` | 手工创建 `<uploadId>/00001.part` + meta(parts={}),启 store → part 被删 |
| `recovery_completedObjectOrphanDirDeleted` | put ObjectStore 成功 + 手工创建孤儿 .multipart/uid,启 store → dir 被删 |
| `recovery_orphanedCompletingRestored` | 手工 rename meta.json → meta.json.completing,启 store → 恢复回 meta.json |
| `recovery_garbageDirDeleted` | 手工创建 dir 但没 meta.json,启 store → dir 被删 |

---

## 十一、风险与未来工作

### 11.1 已知的硬约束

1. **Phase 3 单进程 / 共享盘**:客户端必须按 uploadId 路由到固定节点,跨节点并发 uploadPart 同 uploadId 会丢 part 元数据(§7)。
2. **meta.json 写并发**:同 uploadId 跨进程并发写 meta.json 时,last-writer-wins 丢更新;Phase 3 假设不存在。
3. **listUploads 性能**:每调一次全量扫盘,O(upload count) IO;Phase 4 加内存 cache + mtime 失效。
4. **大量小 part 时 meta.json 大**:每 part 一行 JSON,1k parts ≈ 几十 KB,可读但不便手编;Phase 3 接受。
5. **崩溃期 .completing 状态**:重启恢复只能恢复 meta.json,无法自动重试 complete(可能网络已断,ObjectStore 没收到 put);客户端感知为 NO_SUCH_UPLOAD 时需重新 Initiate。

### 11.2 Phase 4 候选

- SQLite 替代 JSON 元数据(并发 + 事务)
- 分布式锁(etcd / Redis)支持真多节点 active-active
- 后台 worker 定期清理 orphan(基于 TTL,不依赖启动扫)
- listUploads 内存 cache + mtime 失效
- part 去重(同 ETag 的 part bytes 软链复用,适合多 upload 同一文件的场景)

### 11.3 实现里程碑

| 里程碑 | 内容 | 验收 |
|--------|------|------|
| M1 | `DiskObjectStore` 单跑通 put/get/head/delete/list/close | DiskObjectStoreTest 全绿 |
| M2 | `DiskMultipartStore` 基础(initiate/uploadPart/complete/abort)+ 内存锁 + 流式 | DiskMultipartStoreTest 全绿 |
| M3 | `DiskMultipartStore.listUploads/listParts` + meta.json 读写 | DiskMultipartStoreTest 全绿 |
| M4 | 启动恢复(§9)+ 异常路径覆盖 | DiskMultipartStoreRecoveryIT 全绿 |
| M5 | 集成测试(e2e / crash / 并发) | DiskMultipartStoreIT 全绿 |
| M6 | 行为对齐验证(跟 InMemory 同 fixture) | DiskInMemoryEquivalenceTest 全绿 |
| M7 | S3ServerBuilder 文档更新 + README 用法示例 | - |

---

## 十二、调用方集成示意

```java
// 启动期
Path dataRoot = Paths.get("/data/s3");
DiskBucketStore   bucketStore = new DiskBucketStore(dataRoot);
DiskObjectStore   objectStore = new DiskObjectStore(dataRoot, bucketStore);
DiskMultipartStore multipart  = new DiskMultipartStore(dataRoot, bucketStore, objectStore);
// multipart.startupRecovery() 由 ctor 自动跑;也可以显式调一次

HttpServer server = new S3ServerBuilder()
    .bucketStore(bucketStore)
    .objectStore(objectStore)
    .multipartStore(multipart)
    .accessKeyResolver(...)
    .registerMultipartHandlers()    // 一次性挂 6 个 multipart handler
    .listen(9000)
    .build();
server.start();
```

切换 impl 不用动业务代码:handler / IT 全部走 SPI,内存版 / 磁盘版只是两个实现。

---

## 附录 A:与 InMemoryMultipartStore 的对照

| 维度 | InMemoryMultipartStore | DiskMultipartStore |
|------|----------------------|---------------------|
| part bytes 存放 | `byte[]`(堆) | 文件 `<dir>/<%05d>.part` |
| complete 拼装 | `new byte[totalSizeInt]` + arraycopy | `SequenceInputStream` 串流 |
| 元数据 | `ConcurrentMap<String, MultipartUpload>` | `<dir>/meta.json` |
| complete 占所有权 | `uploads.remove(uploadId)`(原子) | `meta.json` → `meta.json.completing` rename |
| 崩溃恢复 | 全部丢失 | 启动扫盘(§9) |
| 多实例 | 不支持(JVM 局部状态) | 共享文件系统支持(单节点路由) |
| 内存上限 | 单 part 5GB OOM | 几乎无限(流式) |
| 适合场景 | 单测 / 本地 dev | 生产 / GB 级 |
