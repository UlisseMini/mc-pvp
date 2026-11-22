//! Native geometry for the Python duel arena. No game state is owned by the library.
use std::slice;

const BLOCKS: usize = 3;
const GRID: usize = 81;
const RAYS: usize = 48;
const PROJECTILES: usize = 12;
const TOKEN: usize = 9;
const WIDTH: usize = GRID * 2 + RAYS + PROJECTILES * TOKEN;
const HALF_WIDTH: f64 = 0.6_f32 as f64 / 2.;
const HEIGHT: f64 = 1.8_f32 as f64;
type Vec3 = [f64; 3];
type Bounds = [f32; 6];

#[inline]
fn vec3(v: &[f32]) -> Vec3 {
    [v[0] as f64, v[1] as f64, v[2] as f64]
}

#[inline]
fn intersect(p: Vec3, d: Vec3, boxes: &[Bounds], active: &[u8]) -> f64 {
    let mut best = f64::INFINITY;
    for (b, active) in boxes.iter().zip(active) {
        if *active == 0 {
            continue;
        }
        let mut first: f64 = 0.;
        let mut last: f64 = best.min(1.);
        for axis in 0..3 {
            let low = b[axis] as f64;
            let high = b[axis + 3] as f64;
            if d[axis].abs() <= 1e-7 {
                if p[axis] < low || p[axis] > high {
                    last = -1.;
                    break;
                }
            } else {
                let a = (low - p[axis]) / d[axis];
                let b = (high - p[axis]) / d[axis];
                first = first.max(a.min(b));
                last = last.min(a.max(b));
                if first > last {
                    break;
                }
            }
        }
        if first <= last {
            best = first;
            if best == 0. {
                break;
            }
        }
    }
    best
}

/// Advance both player boxes in every active world. Inputs use blocks per tick.
#[no_mangle]
pub unsafe extern "C" fn bow_move(
    rows: usize,
    pos: *mut [f32; 3],
    vel: *mut [f32; 3],
    ground: *mut u8,
    boxes: *const Bounds,
    active: *const u8,
    go: *const u8,
    drag: *const f64,
) {
    let pos = slice::from_raw_parts_mut(pos, rows * 2);
    let vel = slice::from_raw_parts_mut(vel, rows * 2);
    let ground = slice::from_raw_parts_mut(ground, rows * 2);
    let boxes = slice::from_raw_parts(boxes, rows * BLOCKS);
    let active = slice::from_raw_parts(active, rows * BLOCKS);
    let go = slice::from_raw_parts(go, rows);
    let drag = slice::from_raw_parts(drag, rows * 2);
    for row in 0..rows {
        if go[row] == 0 {
            continue;
        }
        let blocks = row * BLOCKS;
        let mask = active[blocks..blocks + BLOCKS]
            .iter()
            .enumerate()
            .fold(0u8, |bits, (i, &v)| bits | ((v != 0) as u8) << i);
        for player in [row * 2, row * 2 + 1] {
            for (axis, other) in [(1, [0, 2]), (0, [1, 2]), (2, [0, 1])] {
                let p = vec3(&pos[player]);
                let lo = [p[0] - HALF_WIDTH, p[1], p[2] - HALF_WIDTH];
                let hi = [p[0] + HALF_WIDTH, p[1] + HEIGHT, p[2] + HALF_WIDTH];
                let (mut lower, mut upper) = match axis {
                    1 => (-p[1], f64::INFINITY),
                    0 => (-17.7_f32 as f64 - p[0], 17.7_f32 as f64 - p[0]),
                    _ => (-12.7_f32 as f64 - p[2], 12.7_f32 as f64 - p[2]),
                };
                let mut bits = mask;
                while bits != 0 {
                    let b = boxes[blocks + bits.trailing_zeros() as usize];
                    bits &= bits - 1;
                    if !other
                        .iter()
                        .all(|&i| hi[i] > b[i] as f64 + 1e-6 && lo[i] < b[i + 3] as f64 - 1e-6)
                    {
                        continue;
                    }
                    let up = b[axis] as f64 - hi[axis];
                    if up >= -1e-6 {
                        upper = upper.min(up.max(0.));
                    }
                    let down = b[axis + 3] as f64 - lo[axis];
                    if down <= 1e-6 {
                        lower = lower.max(down.min(0.));
                    }
                }
                let wanted = vel[player][axis];
                let advance = (wanted as f64).max(lower).min(upper);
                let clipped = (advance - wanted as f64).abs() > 1e-7;
                pos[player][axis] = (p[axis] + advance) as f32;
                let v = if clipped { 0. } else { wanted };
                vel[player][axis] = if axis == 1 {
                    ground[player] = (clipped && wanted < 0.) as u8;
                    (v - 0.08_f32) * 0.98_f32
                } else {
                    (v as f64 * drag[player]) as f32
                };
            }
        }
    }
}

/// The caller supplies contiguous arrays of the lengths derived from `rows` and `rays`.
#[no_mangle]
pub unsafe extern "C" fn bow_terrain(
    rows: usize,
    rays: usize,
    boxes: *const Bounds,
    active: *const u8,
    origin: *const Vec3,
    stride: usize,
    delta: *const Vec3,
    output: *mut f64,
) {
    let boxes = slice::from_raw_parts(boxes, rows * BLOCKS);
    let active = slice::from_raw_parts(active, rows * BLOCKS);
    let origins = slice::from_raw_parts(origin, rows * stride);
    let deltas = slice::from_raw_parts(delta, rows * rays);
    let output = slice::from_raw_parts_mut(output, rows * rays);
    for row in 0..rows {
        let ix = row * BLOCKS..(row + 1) * BLOCKS;
        for ray in 0..rays {
            let n = row * rays + ray;
            let p = origins[row * stride + if stride == 1 { 0 } else { ray }];
            output[n] = intersect(p, deltas[n], &boxes[ix.clone()], &active[ix.clone()]);
        }
    }
}

#[inline]
fn rotate(x: f64, z: f64, sin: f64, cos: f64) -> (f64, f64) {
    (cos * x + sin * z, -sin * x + cos * z)
}

#[inline]
fn boundary(p: f64, d: f64, half: f64) -> f64 {
    let numerator = if d > 0. { half - p } else { -half - p };
    let value = numerator / if d.abs() > 1e-6 { d } else { 1e-6 };
    if value >= 0. {
        value
    } else {
        f64::INFINITY
    }
}

#[allow(clippy::too_many_arguments)]
fn describe(
    p: Vec3,
    yaw: f32,
    seat: i32,
    boxes: &[Bounds],
    active: &[u8],
    projectile_pos: &[[f32; 3]],
    projectile_vel: &[[f32; 3]],
    projectile_age: &[i32],
    pearl: &[u8],
    output: &mut [f32],
) {
    let angle = (yaw as f64).to_radians();
    let (sin, cos) = angle.sin_cos();

    for x in -4..=4 {
        for z in -4..=4 {
            let i = ((x + 4) * 9 + z + 4) as usize;
            let (dx, dz) = rotate(x as f64, z as f64, -sin, cos);
            let (x, z) = (p[0] + dx, p[2] + dz);
            let mut top: f64 = 0.;
            for (b, active) in boxes.iter().zip(active) {
                if *active != 0
                    && x >= b[0] as f64
                    && x <= b[3] as f64
                    && z >= b[2] as f64
                    && z <= b[5] as f64
                {
                    top = top.max(b[4] as f64);
                }
            }
            output[i] = ((top - p[1]).clamp(-3., 3.) / 3.) as f32;
            output[GRID + i] = (top > p[1] + 0.01
                || x.abs() > 17.7_f32 as f64
                || z.abs() > 12.7_f32 as f64) as u8 as f32;
        }
    }

    cover_rays(
        p,
        angle,
        boxes,
        active,
        &mut output[2 * GRID..2 * GRID + RAYS],
    );

    let eye = [p[0], p[1] + 1.62, p[2]];
    let start = 2 * GRID + RAYS;
    output[start..].fill(0.);
    for i in 0..PROJECTILES {
        if projectile_age[i] <= 0 {
            continue;
        }
        let a = vec3(&projectile_pos[i]);
        let d = [a[0] - eye[0], a[1] - eye[1], a[2] - eye[2]];
        if d[0] * d[0] + d[1] * d[1] + d[2] * d[2] >= 36. * 36.
            || intersect(eye, d, boxes, active).is_finite()
        {
            continue;
        }
        let v = vec3(&projectile_vel[i]);
        let (x, z) = rotate(d[0], d[2], sin, cos);
        let (vx, vz) = rotate(v[0], v[2], sin, cos);
        output[start + i * TOKEN..start + (i + 1) * TOKEN].copy_from_slice(&[
            (x / 24.) as f32,
            (d[1] / 24.) as f32,
            (z / 24.) as f32,
            (vx / 3.) as f32,
            (v[1] / 3.) as f32,
            (vz / 3.) as f32,
            pearl[i] as f32,
            (i / (PROJECTILES / 2) == seat as usize) as u8 as f32,
            projectile_age[i].min(40) as f32 / 40.,
        ]);
    }
}

fn cover_rays(p: Vec3, angle: f64, boxes: &[Bounds], active: &[u8], out: &mut [f32]) {
    let mut dx = [0.; 16];
    let mut dz = [0.; 16];
    let mut wall = [0.; 16];
    for i in 0..16 {
        let (s, c) = (angle + (i as f64 * 22.5).to_radians()).sin_cos();
        dx[i] = -24. * s;
        dz[i] = 24. * c;
        wall[i] = boundary(p[0], dx[i], 18.)
            .min(boundary(p[2], dz[i], 13.))
            .min(1.);
    }
    let mask = active
        .iter()
        .enumerate()
        .fold(0u8, |bits, (i, &v)| bits | ((v != 0) as u8) << i);
    if mask == 0 {
        for (values, end) in out.chunks_exact_mut(3).zip(wall) {
            values.fill(end as f32);
        }
        return;
    }
    #[cfg(all(target_arch = "x86_64", not(bow_scalar)))]
    if std::arch::is_x86_feature_detected!("avx2") {
        // Each lane covers a different bearing. Heights reuse the horizontal slabs.
        unsafe { avx::cover(p, boxes, mask, &dx, &dz, wall, out) };
        return;
    }
    for i in 0..16 {
        let delta = [dx[i], 0., dz[i]];
        for h in 0..3 {
            let origin = [p[0], p[1] + h as f64 + 0.5, p[2]];
            out[3 * i + h] = wall[i].min(intersect(origin, delta, boxes, active)) as f32;
        }
    }
}

#[cfg(all(target_arch = "x86_64", not(bow_scalar)))]
mod avx {
    use super::{Bounds, Vec3};
    use std::arch::x86_64::*;

    #[target_feature(enable = "avx2")]
    unsafe fn slab(p: f64, low: f32, high: f32, d: __m256d) -> (__m256d, __m256d) {
        let low = low as f64 - p;
        let high = high as f64 - p;
        let abs = _mm256_andnot_pd(_mm256_set1_pd(-0.), d);
        let moving = _mm256_cmp_pd::<_CMP_GT_OQ>(abs, _mm256_set1_pd(1e-7));
        let safe = _mm256_blendv_pd(_mm256_set1_pd(1.), d, moving);
        let a = _mm256_div_pd(_mm256_set1_pd(low), safe);
        let b = _mm256_div_pd(_mm256_set1_pd(high), safe);
        let enter = if low > 0. || high < 0. {
            f64::INFINITY
        } else {
            f64::NEG_INFINITY
        };
        (
            _mm256_blendv_pd(_mm256_set1_pd(enter), _mm256_min_pd(a, b), moving),
            _mm256_blendv_pd(_mm256_set1_pd(f64::INFINITY), _mm256_max_pd(a, b), moving),
        )
    }

    #[target_feature(enable = "avx2")]
    pub unsafe fn cover(
        p: Vec3,
        boxes: &[Bounds],
        mask: u8,
        dx: &[f64; 16],
        dz: &[f64; 16],
        wall: [f64; 16],
        out: &mut [f32],
    ) {
        for first in (0..16).step_by(4) {
            let x = _mm256_loadu_pd(dx[first..].as_ptr());
            let z = _mm256_loadu_pd(dz[first..].as_ptr());
            let mut nearest = [_mm256_loadu_pd(wall[first..].as_ptr()); 3];
            let mut active = mask;
            while active != 0 {
                let i = active.trailing_zeros() as usize;
                active &= active - 1;
                let b = boxes[i];
                let (x0, x1) = slab(p[0], b[0], b[3], x);
                let (z0, z1) = slab(p[2], b[2], b[5], z);
                let enter = _mm256_max_pd(_mm256_setzero_pd(), _mm256_max_pd(x0, z0));
                let leave = _mm256_min_pd(_mm256_set1_pd(1.), _mm256_min_pd(x1, z1));
                let hit = _mm256_cmp_pd::<_CMP_LE_OQ>(enter, leave);
                let cover = _mm256_blendv_pd(_mm256_set1_pd(1.), enter, hit);
                for (h, end) in nearest.iter_mut().enumerate() {
                    let y = p[1] + h as f64 + 0.5;
                    if y >= b[1] as f64 && y <= b[4] as f64 {
                        *end = _mm256_min_pd(*end, cover);
                    }
                }
            }
            for (h, end) in nearest.into_iter().enumerate() {
                let mut values = [0.; 4];
                _mm256_storeu_pd(values.as_mut_ptr(), end);
                for (i, value) in values.into_iter().enumerate() {
                    out[3 * (first + i) + h] = value as f32;
                }
            }
        }
    }
}

/// The caller supplies contiguous, non-overlapping arrays and separate output storage.
#[no_mangle]
pub unsafe extern "C" fn bow_view(
    rows: usize,
    pos: *const [f32; 3],
    yaw: *const f32,
    seat: *const i32,
    boxes: *const Bounds,
    active: *const u8,
    projectile_pos: *const [f32; 3],
    projectile_vel: *const [f32; 3],
    projectile_age: *const i32,
    pearl: *const u8,
    output: *mut f32,
) {
    let pos = slice::from_raw_parts(pos, rows);
    let yaw = slice::from_raw_parts(yaw, rows);
    let seat = slice::from_raw_parts(seat, rows);
    let boxes = slice::from_raw_parts(boxes, rows * BLOCKS);
    let active = slice::from_raw_parts(active, rows * BLOCKS);
    let projectile_pos = slice::from_raw_parts(projectile_pos, rows * PROJECTILES);
    let projectile_vel = slice::from_raw_parts(projectile_vel, rows * PROJECTILES);
    let projectile_age = slice::from_raw_parts(projectile_age, rows * PROJECTILES);
    let pearl = slice::from_raw_parts(pearl, rows * PROJECTILES);
    let output = slice::from_raw_parts_mut(output, rows * WIDTH);
    for row in 0..rows {
        let blocks = row * BLOCKS..(row + 1) * BLOCKS;
        let projectiles = row * PROJECTILES..(row + 1) * PROJECTILES;
        describe(
            vec3(&pos[row]),
            yaw[row],
            seat[row],
            &boxes[blocks.clone()],
            &active[blocks],
            &projectile_pos[projectiles.clone()],
            &projectile_vel[projectiles.clone()],
            &projectile_age[projectiles.clone()],
            &pearl[projectiles],
            &mut output[row * WIDTH..(row + 1) * WIDTH],
        );
    }
}
