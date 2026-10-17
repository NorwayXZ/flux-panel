#!/usr/bin/env python3
"""Build a pinned, GPL-licensed myst consumer with the shared-payment hook."""
import argparse
import pathlib
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent
REVISION = "46f6d152f58cb66df8f74f882f32df0fb258d955"  # upstream 1.39.7
parser = argparse.ArgumentParser()
parser.add_argument("checkout", type=pathlib.Path)
parser.add_argument("--prepare-only", action="store_true")
args = parser.parse_args()
checkout = args.checkout.resolve()
if not checkout.exists():
    subprocess.run(["git", "clone", "--branch", "1.39.7", "--depth", "1", "https://github.com/mysteriumnetwork/node.git", str(checkout)], check=True)
revision = subprocess.check_output(["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True).strip()
if revision != REVISION:
    raise SystemExit("Refusing to patch an unrecognized upstream revision")
for source in (ROOT / "overlay").rglob("*.go"):
    target = checkout / source.relative_to(ROOT / "overlay")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
payer = checkout / "session/pingpong/invoice_payer.go"
text = payer.read_text()
anchor = "func (ip *InvoicePayer) issueExchangeMessage(invoice crypto.Invoice) error {\n"
hook = "\tif SharedBillingEnabled() { return ip.issueSharedExchangeMessage(invoice) }\n"
if "if SharedBillingEnabled()" not in text:
    if text.count(anchor) != 1:
        raise SystemExit("Upstream payment entry point changed")
    payer.write_text(text.replace(anchor, anchor + hook))
di = checkout / "cmd/di.go"
text = di.read_text()
anchor = "\tif err := di.bootstrapDiscoveryComponents(nodeOptions.Discovery); err != nil {"
hook = "\tif err := pingpong.StartSharedBillingServer(di.Keystore, di.HermesCaller, di.AddressProvider, nodeOptions.Directories.Storage); err != nil { return err }\n\n"
if "pingpong.StartSharedBillingServer(" not in text:
    if text.count(anchor) != 1:
        raise SystemExit("Upstream initialization changed")
    di.write_text(text.replace(anchor, hook + anchor))
subprocess.run(["gofmt", "-w", str(di), str(checkout / "session/pingpong/shared_billing_server.go")], check=True)
subprocess.run(["gofmt", "-w", str(payer), str(checkout / "session/pingpong/shared_billing.go")], check=True)
if not args.prepare_only:
    subprocess.run(["go", "build", "-trimpath", "-o", "build/myst", "./cmd/mysterium_node"], cwd=checkout, check=True)
