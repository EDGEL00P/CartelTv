"""Simple DAG-style orchestrator with retries and task groups."""

from __future__ import annotations

import time
import traceback
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional

from .monitoring import Metrics


@dataclass
class Task:
    name: str
    fn: Callable
    deps: List[str] = field(default_factory=list)
    retries: int = 2
    backoff: float = 2.0


class Orchestrator:
    def __init__(self, metrics: Optional[Metrics] = None):
        self.tasks: Dict[str, Task] = {}
        self.metrics = metrics or Metrics()
        self.results: Dict[str, object] = {}
        self.errors: Dict[str, str] = {}

    def add(self, task: Task) -> None:
        self.tasks[task.name] = task

    def _run_one(self, task: Task) -> None:
        last_exc: Optional[Exception] = None
        for attempt in range(task.retries + 1):
            try:
                self.results[task.name] = task.fn(self.results)
                return
            except Exception as exc:  # noqa: BLE001
                last_exc = exc
                time.sleep(task.backoff * (attempt + 1))
        self.errors[task.name] = "".join(traceback.format_exception(type(last_exc), last_exc, last_exc.__traceback__))
        raise last_exc  # type: ignore[misc]

    def run(self, order: Optional[List[str]] = None) -> Dict[str, object]:
        order = order or list(self.tasks.keys())
        done: set = set()
        pending = list(order)
        while pending:
            progressed = False
            for name in list(pending):
                t = self.tasks[name]
                if all(d in done for d in t.deps):
                    self._run_one(t)
                    done.add(name)
                    pending.remove(name)
                    progressed = True
            if not progressed:
                raise RuntimeError(f"cycle or missing dep in {pending}")
        return self.results