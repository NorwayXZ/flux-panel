#!/usr/bin/env python3
"""Regenerate the model's API reference from controller source, without credentials."""
from pathlib import Path
import json
import re
root = Path(__file__).resolve().parents[1]
rows = []
for source in sorted((root / 'springboot-backend/src/main/java/com/admin/controller').glob('*.java')):
    text = source.read_text()
    base = re.search(r'@RequestMapping\("([^"]+)"\)', text)
    if not base or not base[1].startswith('/api/v1/'):
        continue
    routes = [m[1].upper() + ' ' + base[1] + m[2]
              for m in re.finditer(r'@(Post|Get|Put|Delete)Mapping\("([^"]+)"\)', text)]
    rows.append({'module': source.stem, 'routes': routes})
output = root / 'springboot-backend/src/main/resources/ai/panel-api-catalog.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n')
