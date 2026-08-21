# MCP Server standalone module

This folder is the complete Maven application and may be copied without its repository parent.
Run every command below from this folder.

## Prerequisites

- Java 17 or newer and Maven 3.9 or newer.
- Windows semantic search requires an **x64 JDK** and the latest
  [Microsoft Visual C++ 2015–2022 Redistributable (x64)](https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist?view=msvc-170).
  ONNX Runtime's Windows DLLs depend on that runtime. File management and keyword search can still
  run without ONNX models.

Check the Java architecture in PowerShell:

```powershell
.\scripts\check-onnx-prereqs.ps1
```

It should report `amd64` and show every required DLL as present.

## Windows/offline build

Use the model-light build when Maven cannot reach Hugging Face or the ONNX files will be installed
manually. `clean` is important because it removes model files left in `target` by an earlier build.

```powershell
mvn clean package -Dskip.models=true
java -jar target\mcp-server.jar
```

Open `http://127.0.0.1:8080/plugins`, download the pinned embedding and reranker model/tokenizer
pairs on any permitted machine, and upload each pair under **ONNX model files**. Uploads verify the
pinned SHA-256 digests before replacing the installed files.

## Build with bundled models

```powershell
mvn clean package
java -jar target\mcp-server.jar
```

This build runs real embedding and cross-encoder tests. If both report that the model is not ready,
look at the assertion description in `target\surefire-reports`. It now includes the native loader
error. `UnsatisfiedLinkError`, `Can't find dependent libraries`, or a missing `VCRUNTIME`/`MSVCP`
DLL means the Windows x64 Visual C++ runtime is missing or blocked.

```powershell
Select-String -Path target\surefire-reports\*.txt `
  -Pattern "ONNX model should load|Embedding model unavailable|Cross-encoder unavailable|UnsatisfiedLinkError"
```

If the machine is intentionally running keyword-only while the native prerequisite is being fixed,
exclude only the two real-model checks while retaining the rest of the test suite:

```powershell
mvn clean package -Dskip.models=true -DexcludedGroups=onnx
```

Do not use that command as evidence that semantic search works; run `.\scripts\run-eval.ps1` after
the x64 runtime is available.

## Golden-set evaluation

Fixtures, runners, models, and generated reports all resolve within this folder.

```powershell
.\scripts\run-eval.ps1
```

The report is written to `eval-runs\<run-id>\report.json`. The runner uses complete models under
`models\` when available; otherwise Maven downloads the pinned bundles.
