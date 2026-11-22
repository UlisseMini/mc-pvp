"""Batched, approximate 20 Hz bow duels on original arenas."""
import numpy as np

ARROWS = 12
TURN = np.array([35, 20], dtype=np.float32)
KEYS = (3, 3, 2, 2, 2, 2, 2, 2)  # Forward, strafe, jump, sprint, sneak, use, attack, slot.
CORE = 47
TERRAIN = 9 * 9 * 2 + 16 * 3
PROJ = 9
SIZE = CORE + TERRAIN + ARROWS * PROJ
MOUSE = slice(len(KEYS), len(KEYS) + 2)
WALL = np.array([17.7, 12.7], dtype=np.float32)
EYE = 1.62
AIR_DRAG = float(np.float32(.91))
FLOOR_DRAG = float(np.float32(.6) * np.float32(.91))
ARROW_DRAG = float(np.float32(.99))
ARROW_GRAVITY = float(np.float32(.05))
PEARL_GRAVITY = float(np.float32(.03))
HALF_WIDTH = float(np.float32(.6)) / 2
HEIGHT = float(np.float32(1.8))


def sq(x):
    return (x * x).sum(axis=-1)


def wrap(degrees):
    return (degrees + 180) % 360 - 180


def direction(yaw, pitch):
    yaw, pitch = np.deg2rad(yaw), np.deg2rad(pitch)
    return np.stack((-np.sin(yaw) * np.cos(pitch), -np.sin(pitch),
                     np.cos(yaw) * np.cos(pitch)), axis=-1)


def local(vector, yaw):
    angle = np.deg2rad(yaw)
    x, y, z, angle = np.broadcast_arrays(*np.moveaxis(vector, -1, 0), angle)
    return np.stack((np.cos(angle) * x + np.sin(angle) * z, y,
                     -np.sin(angle) * x + np.cos(angle) * z), axis=-1)


def crossing(origin, delta, low, high):
    """First segment contact in [0, 1], or infinity."""
    moving = np.abs(delta) > 1e-7
    safe = np.where(moving, delta, 1)
    a, b = (low - origin) / safe, (high - origin) / safe
    parallel_outside = (~moving & ((origin < low) | (origin > high))).any(axis=-1)
    enter = np.maximum(np.where(moving, np.minimum(a, b), -np.inf).max(axis=-1), 0)
    leave = np.minimum(np.where(moving, np.maximum(a, b), np.inf).min(axis=-1), 1)
    return np.where((enter <= leave) & ~parallel_outside, enter, np.inf)


class Arena:
    def __init__(self, count, seed, limit=640, evaluation=False, paired=False):
        self.n, self.limit, self.evaluation, self.paired = count, limit, evaluation, paired
        self.rng = np.random.default_rng(seed)
        self.rows = np.arange(count)
        self.owner = np.arange(ARROWS) // (ARROWS // 2)
        self.pos = np.zeros((count, 2, 3), np.float32)
        self.vel = np.zeros_like(self.pos)
        self.yaw = np.zeros((count, 2), np.float32)
        self.pitch = np.zeros_like(self.yaw)
        self.hp = np.zeros_like(self.yaw)
        self.shape = (count, 2)
        for name in ('draw', 'hurt', 'slot', 'shots', 'hits', 'swings', 'seen_age', 'seen_draw'):
            setattr(self, name, np.zeros(self.shape, np.int32))
        for name in ('pearl', 'ground', 'sneak', 'sprint', 'use', 'attack', 'seen_use'):
            setattr(self, name, np.zeros(self.shape, bool))
        self.previous = np.zeros((*self.shape, len(KEYS) + 2), np.float32)
        self.last_seen = np.zeros_like(self.pos)
        self.seen_history = np.zeros((*self.shape, 9, 3), np.float32)
        self.visible_history = np.zeros((*self.shape, 9), bool)
        self.boxes = np.zeros((count, 3, 2, 3), np.float32)
        self.active_box = np.zeros((count, 3), bool)
        self.ap = np.zeros((count, ARROWS, 3), np.float32)
        self.av = np.zeros_like(self.ap)
        self.age = np.zeros((count, ARROWS), np.int32)
        self.apearl = np.zeros((count, ARROWS), bool)
        self.crit = np.zeros_like(self.apearl)
        self.t = np.zeros(count, np.int32)
        self.variant = np.zeros(count, np.int32)
        self.reset(self.rows)

    def reset(self, rows):
        n = len(rows)
        if not n:
            return
        m = n // 2 if self.paired else n
        if self.paired and n % 2:
            raise ValueError("Paired initializations require an even batch")
        rep = 2 if self.paired else 1
        def draws(shape, low, high):
            return self.rng.uniform(low, high, (m, *shape)).repeat(rep, axis=0)
        self.t[rows] = 0
        self.variant[rows] = self.rng.integers(0, 3, m).repeat(rep)
        self.pos[rows] = 0
        self.pos[rows, :, 0] = np.array([-1, 1]) * draws((2,), 9.25, 11.45)
        self.pos[rows, :, 2] = draws((2,), -1.8, 1.8)
        delta = self.pos[rows, ::-1] - self.pos[rows]
        self.yaw[rows] = np.rad2deg(np.arctan2(-delta[..., 0], delta[..., 2])) + draws((2,), -12, 12)
        self.pitch[rows] = draws((2,), -5, 5)
        self.hp[rows] = 20
        self.ground[rows] = self.pearl[rows] = True
        for name in ('vel', 'draw', 'hurt', 'slot', 'shots', 'hits', 'swings', 'sneak', 'sprint',
                     'use', 'attack', 'seen_draw', 'seen_use', 'previous', 'last_seen',
                     'seen_history', 'visible_history', 'age'):
            getattr(self, name)[rows] = 0
        self.previous[rows, :, :2] = 1
        self.vel[rows, :, 1] = -.0784
        self.seen_age[rows] = self.limit
        shift = draws((), -1, 1)
        self.boxes[rows] = np.array([[[-7, 0, 3.5], [-5, 3, 5.5]],
                                     [[5, 0, -5.5], [7, 3, -3.5]],
                                     [[-1.5, 0, -1], [1.5, 1.8, 1]]])
        self.boxes[rows, :, :, 2] += shift[:, None, None]
        self.active_box[rows] = self.variant[rows, None] >= [1, 1, 2]
        if self.evaluation:
            # Different cover widths and heights for evaluation, with the same action interface.
            self.boxes[rows, :, 1, 1] += .5
            self.boxes[rows, :2, 1, 2] += .75

    def terrain_hit(self, origin, delta, rows=None):
        boxes = self.boxes if rows is None else self.boxes[rows]
        active = self.active_box if rows is None else self.active_box[rows]
        unsqueeze = (slice(None),) + (None,) * (delta.ndim - 2)
        hit = crossing(origin[..., None, :], delta[..., None, :],
                       boxes[unsqueeze][..., 0, :], boxes[unsqueeze][..., 1, :])
        return np.where(active[unsqueeze], hit, np.inf).min(axis=-1)

    def player_block(self, at):
        low, high = self.boxes[:, None, :, 0], self.boxes[:, None, :, 1]
        body = at[:, :, None, :]
        lo = body - [0.30, 0, 0.30]
        hi = body + [0.30, 1.80, 0.30]
        return (np.all((lo < high - 1e-5) & (hi > low + 1e-5), axis=-1) &
                self.active_box[:, None]).any(axis=-1)

    def move_positions(self, go, drag):
        # Native moveEntity resolves Y, X, then Z against the other two overlapping axes.
        low, high = self.boxes[:, None, :, 0], self.boxes[:, None, :, 1]
        for axis in (1, 0, 2):
            box = self.pos[:, :, None, :]
            lo = box - [HALF_WIDTH, 0, HALF_WIDTH]
            hi = box + [HALF_WIDTH, HEIGHT, HALF_WIDTH]
            other = [i for i in range(3) if i != axis]
            overlaps = ((hi[..., other] > low[..., other] + 1e-6) &
                        (lo[..., other] < high[..., other] - 1e-6)).all(axis=-1) & self.active_box[:, None]
            up = low[..., axis] - hi[..., axis]
            down = high[..., axis] - lo[..., axis]
            upper = np.where(overlaps & (up >= -1e-6), up.clip(min=0), np.inf).min(axis=-1)
            lower = np.where(overlaps & (down <= 1e-6), down.clip(max=0), -np.inf).max(axis=-1)
            at = self.pos[..., axis]
            if axis == 1:
                lower = np.maximum(lower, -at)
            else:
                wall = WALL[axis // 2]
                upper = np.minimum(upper, wall - at)
                lower = np.maximum(lower, -wall - at)
            desired = self.vel[..., axis]
            move = desired.clip(lower, upper)
            clipped = abs(move - desired) > 1e-7
            self.pos[go, :, axis] += move[go]
            if axis == 1:
                self.ground[go] = (clipped & (desired < 0))[go]
                self.vel[go, :, axis] = ((np.where(clipped, 0, desired) - .08) * np.float32(.98))[go]
            else:
                self.vel[go, :, axis] = np.where(clipped, 0, desired)[go] * drag[go]

    def update_vision(self):
        eye = self.pos + [0, EYE, 0]
        delta = self.pos[:, ::-1] + [0, 1.05, 0] - eye
        visible = ~np.isfinite(self.terrain_hit(eye, delta))
        self.last_seen[:] = np.where(visible[..., None], self.pos[:, ::-1], self.last_seen)
        self.seen_history[:, :, 1:] = self.seen_history[:, :, :-1].copy()
        self.seen_history[:, :, 0] = self.last_seen
        self.visible_history[:, :, 1:] = self.visible_history[:, :, :-1].copy()
        self.visible_history[:, :, 0] = visible
        self.seen_age[:] = np.where(visible, 0, self.seen_age + 1)
        drawing = visible & (self.draw[:, ::-1] > 0)
        self.seen_draw[:] = np.where(drawing, self.seen_draw + 1, 0)
        self.seen_use[:] = drawing

    def observe(self, seat):
        r = self.rows
        o = 1 - seat
        p, v, yaw = self.pos[r, seat], self.vel[r, seat], self.yaw[r, seat]
        pitch, age = self.pitch[r, seat], self.seen_age[r, seat]
        visible, known = age == 0, age < self.limit
        relative = local(self.last_seen[r, seat] - p, yaw)
        past = self.seen_history[r, seat]
        history = self.visible_history[r, seat]
        v2 = np.where((visible & history[:, 2])[:, None], (past[:, 0] - past[:, 2]) / 2, 0)
        v8 = np.where((visible & history[:, 8])[:, None], (past[:, 0] - past[:, 8]) / 8, 0)
        facing = np.deg2rad(self.yaw[r, o] - yaw)
        own = np.column_stack((p / [18, 3, 13], v * 3, np.sin(np.deg2rad(yaw)), np.cos(np.deg2rad(yaw)),
                               pitch / 90, self.ground[r, seat], self.sprint[r, seat], self.sneak[r, seat],
                               self.hp[r, seat] / 20, self.hurt[r, seat] / 10, self.draw[r, seat].clip(0, 20) / 20,
                               self.slot[r, seat], self.pearl[r, seat], self.use[r, seat]))
        opp = np.column_stack((relative * known[:, None] / 24,
                               local(v2, yaw) * 3, local(v8, yaw) * 3,
                               np.sin(facing) * visible, np.cos(facing) * visible,
                               self.pitch[r, o] / 90 * visible, self.seen_draw[r, seat].clip(0, 20) / 20,
                               self.slot[r, o] * visible, self.sneak[r, o] & visible,
                               (self.hurt[r, o] > 0) & visible))
        previous = self.previous[r, seat]
        core = np.column_stack((own, opp, visible, age.clip(0, 40) / 40, self.t / self.limit,
                                previous[:, :2] - 1, previous[:, 2:]))
        assert core.shape[1] == CORE, core.shape
        return np.column_stack((core, self.view_features(seat, p, yaw))).astype(np.float32)

    def view_features(self, seat, p, yaw):
        # One-block samples expressed in the look frame, then converted to world positions.
        grid = np.stack(np.meshgrid(np.arange(-4, 5), [0], np.arange(-4, 5), indexing='ij'), axis=-1).reshape(-1, 3)
        points = p[:, None] + local(grid[None], -yaw[:, None])
        lo, hi = self.boxes[:, None, :, 0], self.boxes[:, None, :, 1]
        inside = ((points[:, :, None, [0, 2]] >= lo[..., [0, 2]]) &
                  (points[:, :, None, [0, 2]] <= hi[..., [0, 2]])).all(axis=-1)
        top = np.where(inside & self.active_box[:, None], hi[..., 1], 0).max(axis=-1)
        obstruction = (top > p[:, None, 1] + .01) | (np.abs(points[..., [0, 2]]) > WALL).any(axis=-1)
        bearing = yaw[:, None] + np.repeat(np.arange(16) * 22.5, 3)
        rays = direction(bearing, np.zeros_like(bearing)) * 24
        origin = p[:, None] + np.tile([[0, .5, 0], [0, 1.5, 0], [0, 2.5, 0]], (16, 1))
        walls = np.stack((18 - origin[..., 0], 13 - origin[..., 2]), axis=-1)
        d = rays[..., [0, 2]]
        walls = np.where(d > 0, walls, walls - [36, 26]) / np.where(abs(d) > 1e-6, d, 1e-6)
        walls = np.where(walls >= 0, walls, np.inf).min(axis=-1)
        cover = np.minimum(self.terrain_hit(origin, rays), walls).clip(0, 1)
        terrain = np.column_stack(((top - p[:, None, 1]).clip(-3, 3) / 3, obstruction, cover))
        arrow = self.ap - (p + [0, EYE, 0])[:, None]
        present = ((self.age > 0) & (sq(arrow) < 36**2) &
                   ~np.isfinite(self.terrain_hit((p + [0, EYE, 0])[:, None], arrow)))
        projectiles = np.concatenate((local(arrow, yaw[:, None]) / 24, local(self.av, yaw[:, None]) / 3,
                                      self.apearl[..., None], (self.owner == np.asarray(seat)[..., None])[..., None] * np.ones_like(self.age[..., None]),
                                      np.minimum(self.age[..., None], 40) / 40), axis=-1)
        projectiles *= present[..., None]
        return np.column_stack((terrain, projectiles.reshape(self.n, -1)))

    def launch(self, mask, pearl, speed):
        row, seat = np.where(mask)
        if not len(row):
            return
        yaw, pitch = self.yaw[row, seat], self.pitch[row, seat]
        slot = (self.shots[row, seat] + pearl) % (ARROWS // 2) + seat * (ARROWS // 2)
        self.ap[row, slot] = (self.pos[row, seat] + np.column_stack((
            -.16 * np.cos(np.deg2rad(yaw)), (EYE - .10) - .08 * self.sneak[row, seat],
            -.16 * np.sin(np.deg2rad(yaw)))))
        power = speed[row, seat, None]
        self.av[row, slot] = power * (direction(yaw, pitch) + self.rng.normal(0, .0075, (len(row), 3)))
        self.age[row, slot] = 1
        self.apearl[row, slot] = pearl
        self.crit[row, slot] = (self.draw[row, seat] >= 20) & (not pearl)
        self.shots[row, seat] += not pearl

    def step(self, action, live=None):
        go = np.ones(self.n, bool) if live is None else live
        self.previous[go] = action[go]
        turn = action[..., MOUSE] * TURN
        self.yaw[go] = wrap(self.yaw[go] + turn[go, :, 0])
        self.pitch[go] = np.clip(self.pitch[go] + turn[go, :, 1], -90, 90)
        forward, strafe, jump, sprint, sneak, use, attack, slot = np.moveaxis(action[..., :len(KEYS)], -1, 0)
        self.sneak[go] = sneak[go] > 0
        self.sprint[go] = (sprint[go] > 0) & (forward[go] == 2) & ~self.sneak[go] & (self.draw[go] == 0)
        held = (use > 0) & go[:, None]
        self.hurt[go] = np.maximum(self.hurt[go] - 1, 0)
        self.swings[go] = np.maximum(self.swings[go] - 1, 0)
        release = (~held & self.use & (slot == 0) & (self.slot == 0) & go[:, None])
        draw = self.draw / 20
        power = (draw * draw + 2 * draw) / 3
        self.launch(release & (power >= .1), False, np.minimum(1, power) * 3)
        throw = held & (~self.use | (self.slot != slot)) & (slot == 1) & self.pearl
        self.launch(throw, True, np.full(self.shape, 1.5))
        self.pearl[throw] = False
        self.draw[go] = np.where(held & (slot == 0), np.where(self.slot == 0, self.draw, 0) + 1, 0)[go]
        self.use[go], self.slot[go] = held[go], slot[go]
        ground = self.ground.copy()
        drag = np.where(ground, FLOOR_DRAG, AIR_DRAG)
        movement = np.stack((1 - strafe, np.zeros_like(forward), forward - 1), axis=-1) * .98
        movement *= np.where(self.sneak, .30, 1)[..., None]
        movement *= np.where(self.draw > 0, .20, 1)[..., None]
        acceleration = np.where(ground, .10, .02) * np.where(self.sprint, 1.30, 1)
        motion = local(movement, -self.yaw)
        horizontal_input = motion * (acceleration / np.sqrt(np.maximum(1, sq(motion))))[..., None]
        self.vel[go] = np.where(abs(self.vel[go]) < .005, 0, self.vel[go])
        self.vel[go] += horizontal_input[go]
        takeoff = go[:, None] & ground & (jump > 0)
        self.vel[takeoff, 1] = np.float32(.42)
        impulse = direction(self.yaw, np.zeros_like(self.yaw)) * .20
        self.vel += impulse * (takeoff & self.sprint)[..., None]
        self.move_positions(go, drag)
        self.melee(attack > 0, go)
        self.move_projectiles(go)
        self.t[go] += 1
        dead = self.hp <= 0
        done = dead.any(axis=-1) | (self.t >= self.limit)
        winner = dead[:, 1].astype(np.int8) - dead[:, 0]
        return done, winner

    def melee(self, attack, go):
        start = self.pos + [0, EYE, 0]
        delta = direction(self.yaw, self.pitch) * 3
        opp = self.pos[:, ::-1]
        hit = crossing(start, delta, opp - [.30, 0, .30], opp + [.30, 1.8, .30])
        swing = attack & ~self.attack & (self.swings == 0) & go[:, None]
        self.attack[go] = attack[go]
        self.swings[swing] = 4
        row, seat = np.where(swing & (hit < self.terrain_hit(start, delta)) & (self.hurt[:, ::-1] == 0))
        self.hp[row, 1 - seat] -= .52
        self.hurt[row, 1 - seat] = 10

    def move_projectiles(self, go):
        row, ix = np.where((self.age > 0) & go[:, None])
        if not len(row):
            return
        p, v = self.ap[row, ix], self.av[row, ix]
        seat = self.owner[ix]
        opp = self.pos[row, 1 - seat]
        cover = self.terrain_hit(p, v, row)
        ground = (p[:, 1] + v[:, 1] <= 0) & (v[:, 1] < 0)
        wall = (np.abs((p + v)[:, [0, 2]]) > WALL + .3).any(axis=-1)
        terrain = np.minimum(cover, np.where(ground, np.maximum(0, p[:, 1]) / np.maximum(-v[:, 1], 1e-6), np.inf))
        contact = crossing(p, v, opp - [HALF_WIDTH+.30, .30, HALF_WIDTH+.30],
                           opp + [HALF_WIDTH+.30, HEIGHT+.30, HALF_WIDTH+.30])
        bodies = (contact < terrain) & ~wall
        pearl = self.apearl[row, ix]
        # Deterministic event order within a tick. Multiple arrows can enter the same hurt window.
        for j in np.flatnonzero(bodies & ~pearl):
            a, b = row[j], 1 - seat[j]
            if self.hurt[a, b]:
                continue
            self.hits[a, seat[j]] += 1
            damage = np.ceil(np.linalg.norm(v[j]) * 2)
            if self.crit[row[j], ix[j]]:
                damage += self.rng.integers(0, int(damage) // 2 + 2)
            self.hp[a, b] -= .52 * damage
            self.hurt[a, b] = 10
            horizontal = v[j, [0, 2]]
            away = self.pos[a, b, [0, 2]] - self.pos[a, seat[j], [0, 2]]
            self.vel[a, b] *= .5
            self.vel[a, b, [0, 2]] += .40 * away / max(.01, np.linalg.norm(away))
            self.vel[a, b, [0, 2]] += .60 * horizontal / max(.01, np.linalg.norm(horizontal))
            self.vel[a, b, 1] = min(.40, self.vel[a, b, 1] + .40) + .10
        terminal = bodies | np.isfinite(terrain) | wall
        for j in np.flatnonzero(terminal & pearl & ~wall):
            a, b = row[j], seat[j]
            # The native throwable calls onImpact before advancing its position.
            end = p[j].copy()
            end[1] = max(0, end[1])
            old = self.pos[a, b].copy()
            self.pos[a, b] = end
            if self.player_block(self.pos)[a, b]:
                self.pos[a, b] = old
            else:
                self.vel[a, b] = 0
                self.hp[a, b] -= 5
        self.ap[row, ix] = p + v
        self.av[row, ix] = v * ARROW_DRAG
        self.av[row, ix, 1] -= np.where(pearl, PEARL_GRAVITY, ARROW_GRAVITY)
        self.age[row, ix] = np.where(terminal | (self.age[row, ix] >= 60), 0, self.age[row, ix] + 1)
