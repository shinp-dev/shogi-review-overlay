"""Train the shared 29-class tiny board CNN. Python 3, numpy, torch, Pillow.
Optional local screenshot inputs are never copied into the repository.
Example:
 python tools/train_board_cnn.py --wars path/to/wars.png --quest path/to/quest.png
The optional images must be the exact 691x1536 labelled boards described below.
Without screenshots this trains on existing binary templates only; accuracy differs.
"""
import argparse
import base64
import pathlib
import struct
import numpy as np
import torch
from torch import nn
from torch.nn import functional as F
from PIL import Image

CODES = ".PLNSGBRKTUVWXYplnsgbrktuvwxy"
ASSETS = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/assets"
FILES = [
    "shogi_quest_templates_v1.txt", "shogi_quest_variants_v1.txt",
    "shogi_quest_promoted_v1.txt", "shogi_templates_v1.txt",
    "shogi_wars_variants_v1.txt", "shogi_wars_promoted_v1.txt",
]
WARS_ROWS = ["lnsgkgsnl", ".r.....b.", "pppppp.pp", "......p..",
             ".........", ".......P.", "PPPPPPP.P", ".B.....R.", "LNSGKGSNL"]
QUEST_ROWS = ["ln......T", ".r...k...", ".pp.pg...", "p.....s.p",
              "...pL....", "P.P..N..P", ".PSP....x", "...KL....", "LN..G.y.."]


class Net(nn.Module):
    def __init__(self):
        super().__init__()
        self.c1 = nn.Conv2d(1, 6, 5, 2, 2)
        self.c2 = nn.Conv2d(6, 6, 3, 2, 1)
        self.fc = nn.Linear(96, 29)

    def forward(self, x):
        return self.fc(F.max_pool2d(F.relu(self.c2(F.relu(self.c1(x)))), 2).flatten(1))


def load_templates():
    images, labels = [], []
    for name in FILES:
        for line in (ASSETS / name).read_text().splitlines():
            if len(line) > 2 and line[1] == ":":
                mask = np.unpackbits(np.frombuffer(base64.b64decode(line[2:]), np.uint8))
                images.append(mask.reshape(32, 32))
                labels.append(CODES.index(line[0]))
    return torch.tensor(np.array(images), dtype=torch.float32)[:, None], torch.tensor(labels)


def augment(masks, labels, rng, n=128):
    ix = rng.integers(len(labels), size=n)
    x, y = masks[ix], labels[ix].clone()
    empty = rng.random(n) < .18
    x[empty], y[empty] = 0, 0
    a = rng.uniform(-.09, .09, n)
    t = np.zeros((n, 2, 3), np.float32)
    t[:, 0, 0] = rng.uniform(.88, 1.12, n) * np.cos(a)
    t[:, 1, 1] = rng.uniform(.88, 1.12, n) * np.cos(a)
    t[:, 0, 1], t[:, 1, 0] = -np.sin(a), np.sin(a)
    t[:, :, 2] = rng.uniform(-.13, .13, (n, 2))
    x = F.grid_sample(x, F.affine_grid(torch.tensor(t), x.size(), align_corners=False), align_corners=False)
    bg = torch.tensor(rng.uniform(.10, .46, (n, 1, 1, 1)), dtype=torch.float32)
    fg = torch.tensor(rng.uniform(.70, 1., (n, 1, 1, 1)), dtype=torch.float32)
    grad = torch.tensor(rng.uniform(-.13, .13, (n, 1, 1, 1)), dtype=torch.float32) * torch.linspace(-1, 1, 32)[None, None, None, :]
    return (bg + (fg-bg)*x + grad + torch.randn_like(x)*.035).clamp(0, 1), y


def screen(path, rect, rows):
    image = Image.open(path).convert("RGB")
    if image.size != (691, 1536):
        raise ValueError("Expected exact original 691x1536 labelled screenshot")
    a = np.asarray(image, dtype=np.int32)
    g = (77*a[:,:,0] + 150*a[:,:,1] + 29*a[:,:,2]) / 256
    x, y, w, h = rect
    out = []
    for i in range(81):
        cw, ch = w/9, h/9
        xx = np.floor(x+(i%9+.08)*cw+(np.arange(32)+.5)/32*.84*cw).astype(int)
        yy = np.floor(y+(i//9+.08)*ch+(np.arange(32)+.5)/32*.84*ch).astype(int)
        out.append(1-g[np.ix_(yy,xx)]/255)
    return torch.tensor(np.array(out), dtype=torch.float32)[:,None], torch.tensor([CODES.index(c) for c in "".join(rows)])


def validate(net, x, y, name):
    with torch.no_grad():
        p = net(x).softmax(1)
        v, i = p.max(1)
        ok = i == y
        top = p.topk(2, 1).values
        accepted = (v >= .75) & ((top[:,0]-top[:,1]) >= .25)
        print(name, int(ok.sum()), "/", len(y), "accepted correct", int((ok & accepted).sum()),
              "wrong accepted", int((~ok & accepted).sum()), "min correct probability",
              float(v[ok].min()) if ok.any() else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wars", type=pathlib.Path)
    parser.add_argument("--quest", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, default=ASSETS / "shogi_cnn_v1.txt")
    args = parser.parse_args()
    torch.set_num_threads(4)
    torch.manual_seed(20261125)
    rng = np.random.default_rng(20261125)
    masks, labels = load_templates()
    net = Net()
    opt = torch.optim.AdamW(net.parameters(), lr=.003)
    screens = []
    if args.wars:
        screens.append(("wars", *screen(args.wars, (14,438,664,730), WARS_ROWS)))
    if args.quest:
        screens.append(("quest", *screen(args.quest, (8,275,675,736), QUEST_ROWS)))
    realx, realy, hold = [], [], []
    for name, x, y in screens:
        seen, ids, held = {}, [], []
        for i, c in enumerate(y.tolist()):
            j = seen.get(c, 0)
            seen[c] = j+1
            (held if j%2 else ids).append(i)
        realx.append(x[ids]); realy.append(y[ids]); hold.append((name, x[held], y[held]))
    if realx:
        rx, ry = torch.cat(realx), torch.cat(realy)
    for ep in range(85):
        for _ in range(16):
            x, y = augment(masks, labels, rng)
            if realx:
                ii = rng.integers(len(ry), size=64)
                z = rx[ii]
                a = rng.uniform(-.06, .06, 64)
                t = np.zeros((64,2,3), np.float32)
                t[:,0,0] = rng.uniform(.92,1.08,64)*np.cos(a)
                t[:,1,1] = rng.uniform(.92,1.08,64)*np.cos(a)
                t[:,0,1], t[:,1,0] = -np.sin(a), np.sin(a)
                t[:,:,2] = rng.uniform(-.09,.09,(64,2))
                z = F.grid_sample(z, F.affine_grid(torch.tensor(t),z.size(),align_corners=False), padding_mode="border",align_corners=False)
                z = (z*torch.tensor(rng.uniform(.85,1.15,(64,1,1,1)),dtype=torch.float32)
                    + torch.tensor(rng.uniform(-.06,.06,(64,1,1,1)),dtype=torch.float32)
                    + torch.randn_like(z)*.02).clamp(0,1)
                x, y = torch.cat((x,z)), torch.cat((y,ry[ii]))
            opt.zero_grad()
            loss = F.cross_entropy(net(x),y)
            loss.backward()
            opt.step()
        if ep in [19,39,84]:
            print("epoch", ep+1, "loss", float(loss.detach()))
            for name,x,y in screens:
                validate(net,x,y,name)
    payload = b"SCN1"
    for p in net.parameters():
        a = p.detach().numpy()
        scale = max(float(np.max(np.abs(a)))/127, 1e-8)
        q = np.round(a/scale).clip(-127,127).astype(np.int8)
        payload += struct.pack("<f",scale)+q.tobytes()
        p.data = torch.tensor(q.astype(np.float32)*scale)
    for name,x,y in screens:
        validate(net,x,y,name+" quantized")
    for name,x,y in hold:
        validate(net,x,y,name+" held-out (same screenshot)")
    args.output.write_text(base64.b64encode(payload).decode()+"\n")
    print("model bytes",len(payload))


if __name__ == "__main__":
    main()
