"""Schema validation for guide records/batches."""

from __future__ import annotations

from typing import Any, Dict, List

from .exceptions import ValidationError


def validate_record(record: Dict[str, Any], required: List[str], dtypes: Dict[str, str] | None = None) -> None:
    for field in required:
        if field not in record or record[field] is None:
            raise ValidationError(f"missing required field: {field}")
    if dtypes:
        for field, expected in dtypes.items():
            if field in record and record[field] is not None:
                actual = type(record[field]).__name__
                if expected not in (actual,):
                    # loose check: allow int/float coercion
                    try:
                        if expected == "int" and not isinstance(record[field], int):
                            raise ValidationError(f"{field}: expected int got {actual}")
                        if expected == "str" and not isinstance(record[field], str):
                            raise ValidationError(f"{field}: expected str got {actual}")
                    except ValidationError:
                        raise


def validate_batch(records: List[Dict[str, Any]], required: List[str], max_failed_ratio: float = 0.05) -> List[Dict[str, Any]]:
    ok: List[Dict[str, Any]] = []
    failed = 0
    for r in records:
        try:
            validate_record(r, required)
            ok.append(r)
        except ValidationError:
            failed += 1
    if records and failed / len(records) > max_failed_ratio:
        raise ValidationError(f"failed ratio {failed}/{len(records)} exceeds {max_failed_ratio}")
    return ok