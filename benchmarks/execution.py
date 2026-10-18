import os
import signal
import subprocess
import threading
import time


def run_process(argv: list[str], timeout_ms: int, env: dict[str, str] | None = None) -> dict:
    if not argv or any(not isinstance(arg, str) or not arg for arg in argv):
        raise ValueError("argv must contain nonempty strings")
    if timeout_ms <= 0:
        raise ValueError("timeout_ms must be positive")
    child_env = os.environ.copy()
    if env is not None:
        child_env.update(env)

    started = time.perf_counter_ns()
    try:
        process = subprocess.Popen(
            argv,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            env=child_env,
            start_new_session=(os.name == "posix"),
        )
    except OSError as error:
        return {
            "duration_ns": time.perf_counter_ns() - started,
            "exit_code": 127,
            "stdout": "",
            "stderr": f"{error.strerror}: {error.filename}",
            "timed_out": False,
        }
    timed_out = False
    try:
        try:
            stdout, stderr = process.communicate(timeout=timeout_ms / 1000)
        except subprocess.TimeoutExpired:
            timed_out = True
            _terminate_group(process)
            try:
                stdout, stderr = process.communicate(timeout=2)
            except subprocess.TimeoutExpired as error:
                stdout = error.output or ""
                stderr = error.stderr or ""
    except BaseException:
        # Preserve the original interruption, including one during timeout cleanup.
        # A second terminal SIGINT must not interrupt termination and reaping.
        main_thread = threading.current_thread() is threading.main_thread()
        if main_thread:
            previous_sigint = signal.signal(signal.SIGINT, signal.SIG_IGN)
        try:
            _terminate_group(process)
        finally:
            if main_thread:
                signal.signal(signal.SIGINT, previous_sigint)
        raise
    finally:
        for stream in (process.stdin, process.stdout, process.stderr):
            try:
                stream.close()
            except OSError:
                pass
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    if isinstance(stderr, bytes):
        stderr = stderr.decode("utf-8", errors="replace")
    duration_ns = time.perf_counter_ns() - started
    return {
        "duration_ns": duration_ns,
        "exit_code": None if timed_out else process.returncode,
        "stdout": stdout,
        "stderr": stderr,
        "timed_out": timed_out,
    }


def _terminate_group(process: subprocess.Popen) -> None:
    try:
        if os.name == "posix":
            os.killpg(process.pid, signal.SIGTERM)
        else:
            if process.poll() is not None:
                return
            process.terminate()
    except (ProcessLookupError, PermissionError):
        pass
    try:
        process.wait(timeout=0.5)
    except subprocess.TimeoutExpired:
        pass
    try:
        if os.name == "posix":
            os.killpg(process.pid, signal.SIGKILL)
        elif process.poll() is None:
            process.kill()
    except (ProcessLookupError, PermissionError):
        pass
    if process.poll() is None:
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            pass
