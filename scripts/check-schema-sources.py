#!/usr/bin/env python3
"""Check that the three places defining database tables stay in step.

Tables are defined in gost.sql (fresh installs), in the startup schema
initializers (every install, on each start) and in migrations/ (scripts for
maintaining a database by hand). A table added to a migration script but not
to an initializer would exist only on hand-migrated databases, so that is an
error. Initializer tables without a migration script are listed for
information only.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CREATE = re.compile(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([A-Za-z_][A-Za-z0-9_]*)`?", re.I)


def tables(paths):
    found = {}
    for path in paths:
        for name in CREATE.findall(path.read_text(encoding="utf-8", errors="ignore")):
            found.setdefault(name.lower(), path)
    return found


def main():
    base = tables([ROOT / "gost.sql"])
    runtime = tables(sorted((ROOT / "springboot-backend/src/main/java").rglob("*.java")))
    migrations = tables(sorted((ROOT / "migrations").glob("*.sql")))

    for label, found in (("gost.sql", base), ("startup initializers", runtime), ("migrations", migrations)):
        if not found:
            print(f"No tables found in {label}; the check cannot run.", file=sys.stderr)
            return 1

    missing = sorted(name for name in migrations if name not in runtime and name not in base)
    if missing:
        print("Tables created only by migration scripts:", file=sys.stderr)
        for name in missing:
            print(f"  {name}  ({migrations[name].relative_to(ROOT)})", file=sys.stderr)
        print("Add them to a startup schema initializer or to gost.sql.", file=sys.stderr)
        return 1

    without_script = sorted(name for name in runtime if name not in migrations and name not in base)
    print(
        f"Schema sources agree: gost.sql={len(base)} startup={len(runtime)} migrations={len(migrations)} tables; "
        f"{len(without_script)} startup-only tables have no manual migration script."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
