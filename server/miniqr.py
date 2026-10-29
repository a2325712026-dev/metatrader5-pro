"""极简纯 Python 二维码生成器（仅字节模式、纠错等级 L、自动选版本），无第三方依赖。
Minimal pure-Python QR Code encoder (byte mode, ECC level L, auto version). No dependencies.
用途：在控制台打印“连接码”的二维码（供另一台手机扫码后复制文字）。算法依据 ISO/IEC 18004。
"""

_ECC_PER_BLOCK = [-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30]
_NUM_BLOCKS = [-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25]


def _raw_modules(ver):
    r = (16 * ver + 128) * ver + 64
    if ver >= 2:
        n = ver // 7 + 2
        r -= (25 * n - 10) * n - 55
        if ver >= 7:
            r -= 36
    return r


def _data_codewords(ver):
    return _raw_modules(ver) // 8 - _ECC_PER_BLOCK[ver] * _NUM_BLOCKS[ver]


def _gf_mul(x, y):
    z = 0
    for i in reversed(range(8)):
        z = (z << 1) ^ ((z >> 7) * 0x11D)
        z ^= ((y >> i) & 1) * x
    return z


def _rs_divisor(degree):
    result = [0] * (degree - 1) + [1]
    root = 1
    for _ in range(degree):
        for j in range(degree):
            result[j] = _gf_mul(result[j], root)
            if j + 1 < degree:
                result[j] ^= result[j + 1]
        root = _gf_mul(root, 2)
    return result


def _rs_remainder(data, divisor):
    result = [0] * len(divisor)
    for b in data:
        factor = b ^ result.pop(0)
        result.append(0)
        for i, coef in enumerate(divisor):
            result[i] ^= _gf_mul(coef, factor)
    return result


def _interleave(data, ver):
    nb, ecc_len = _NUM_BLOCKS[ver], _ECC_PER_BLOCK[ver]
    raw = _raw_modules(ver) // 8
    n_short, short_len = nb - raw % nb, raw // nb
    div = _rs_divisor(ecc_len)
    blocks, k = [], 0
    for i in range(nb):
        dat = data[k:k + short_len - ecc_len + (0 if i < n_short else 1)]
        k += len(dat)
        ecc = _rs_remainder(dat, div)
        if i < n_short:
            dat = dat + [0]
        blocks.append(dat + ecc)
    out = []
    for i in range(len(blocks[0])):
        for j, blk in enumerate(blocks):
            if i != short_len - ecc_len or j >= n_short:
                out.append(blk[i])
    return out


def _encode_data(payload: bytes):
    for ver in range(1, 41):
        cap_bits = _data_codewords(ver) * 8
        cc_bits = 8 if ver <= 9 else 16
        if 4 + cc_bits + 8 * len(payload) <= cap_bits:
            break
    else:
        raise ValueError("数据太长，无法生成二维码 / data too long for a QR code")
    bits = [0, 1, 0, 0] + [(len(payload) >> i) & 1 for i in reversed(range(cc_bits))]
    for b in payload:
        bits += [(b >> i) & 1 for i in reversed(range(8))]
    bits += [0] * min(4, cap_bits - len(bits))
    bits += [0] * (-len(bits) % 8)
    data = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]
    pad = 0xEC
    while len(data) < cap_bits // 8:
        data.append(pad)
        pad ^= 0xEC ^ 0x11
    return ver, data


def _align_positions(ver):
    if ver == 1:
        return []
    n = ver // 7 + 2
    step = 26 if ver == 32 else (ver * 4 + n * 2 + 1) // (n * 2 - 2) * 2
    return list(reversed([ver * 4 + 10 - i * step for i in range(n - 1)] + [6]))


def _mask_fn(m):
    return [lambda x, y: (x + y) % 2 == 0, lambda x, y: y % 2 == 0, lambda x, y: x % 3 == 0,
            lambda x, y: (x + y) % 3 == 0, lambda x, y: (x // 3 + y // 2) % 2 == 0,
            lambda x, y: x * y % 2 + x * y % 3 == 0, lambda x, y: (x * y % 2 + x * y % 3) % 2 == 0,
            lambda x, y: ((x + y) % 2 + x * y % 3) % 2 == 0][m]


def _penalty(mod):
    n = len(mod)
    score = 0
    for grid in (mod, [list(c) for c in zip(*mod)]):
        for row in grid:
            run, prev = 0, None
            for v in row:
                if v == prev:
                    run += 1
                    if run == 5:
                        score += 3
                    elif run > 5:
                        score += 1
                else:
                    prev, run = v, 1
            s = "".join("1" if v else "0" for v in row)
            score += 40 * (s.count("000010111010") + s.count("010111010000"))  # 近似 N3
    for y in range(n - 1):
        for x in range(n - 1):
            if mod[y][x] == mod[y][x + 1] == mod[y + 1][x] == mod[y + 1][x + 1]:
                score += 3
    dark = sum(map(sum, mod))
    total = n * n
    score += 10 * max(0, (abs(dark * 20 - total * 10) + total - 1) // total - 1)
    return score


def make_qr(text: str):
    """返回二维码矩阵（list[list[bool]]，True = 深色模块）。"""
    ver, data = _encode_data(text.encode("utf-8"))
    codewords = _interleave(data, ver)
    size = ver * 4 + 17
    mod = [[False] * size for _ in range(size)]
    func = [[False] * size for _ in range(size)]

    def put(x, y, v):
        mod[y][x] = bool(v); func[y][x] = True

    for i in range(size):
        put(6, i, i % 2 == 0); put(i, 6, i % 2 == 0)
    for (cx, cy) in ((3, 3), (size - 4, 3), (3, size - 4)):
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                xx, yy = cx + dx, cy + dy
                if 0 <= xx < size and 0 <= yy < size:
                    put(xx, yy, max(abs(dx), abs(dy)) not in (2, 4))
    pos = _align_positions(ver)
    for i, cy in enumerate(pos):
        for j, cx in enumerate(pos):
            if (i == 0 and j == 0) or (i == 0 and j == len(pos) - 1) or (i == len(pos) - 1 and j == 0):
                continue
            for dy in range(-2, 3):
                for dx in range(-2, 3):
                    put(cx + dx, cy + dy, max(abs(dx), abs(dy)) != 1)

    def draw_format(mask):
        d = (1 << 3) | mask  # ECC L = 01
        rem = d
        for _ in range(10):
            rem = (rem << 1) ^ ((rem >> 9) * 0x537)
        bits = ((d << 10) | rem) ^ 0x5412
        g = lambda i: (bits >> i) & 1
        for i in range(6):
            put(8, i, g(i))
        put(8, 7, g(6)); put(8, 8, g(7)); put(7, 8, g(8))
        for i in range(9, 15):
            put(14 - i, 8, g(i))
        for i in range(8):
            put(size - 1 - i, 8, g(i))
        for i in range(8, 15):
            put(8, size - 15 + i, g(i))
        put(8, size - 8, True)

    draw_format(0)  # 先占位，保证数据区不覆盖格式区
    if ver >= 7:
        rem = ver
        for _ in range(12):
            rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
        bits = (ver << 12) | rem
        for i in range(18):
            b, a = i // 3, size - 11 + i % 3
            put(a, b, (bits >> i) & 1); put(b, a, (bits >> i) & 1)

    i = 0
    right = size - 1
    while right >= 1:
        if right == 6:
            right = 5  # 跳过竖向定时线
        for vert in range(size):
            for j in range(2):
                x = right - j
                y = size - 1 - vert if ((right + 1) & 2) == 0 else vert
                if not func[y][x] and i < len(codewords) * 8:
                    mod[y][x] = bool((codewords[i >> 3] >> (7 - (i & 7))) & 1)
                    i += 1
        right -= 2

    best, best_score = None, None
    for m in range(8):
        f = _mask_fn(m)
        cand = [[mod[y][x] ^ (not func[y][x] and f(x, y)) for x in range(size)] for y in range(size)]
        saved = [row[:] for row in mod]
        mod[:] = cand
        draw_format(m)
        sc = _penalty(mod)
        if best_score is None or sc < best_score:
            best, best_score = [row[:] for row in mod], sc
        mod[:] = saved
    return best


def qr_to_console(matrix, border: int = 3) -> str:
    """半块字符渲染（每两行模块占一行）。浅色模块 = 实心块，深色模块 = 终端背景色（深色背景的控制台适用）。"""
    n = len(matrix)
    def dark(x, y):
        return 0 <= x < n and 0 <= y < n and matrix[y][x]
    lines = []
    for y in range(-border, n + border, 2):
        row = []
        for x in range(-border, n + border):
            top, bot = dark(x, y), dark(x, y + 1)
            row.append(" " if top and bot else "▄" if top else "▀" if bot else "█")
        lines.append("".join(row))
    return "\n".join(lines)
