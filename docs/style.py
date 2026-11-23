"""Shared warm-editorial Matplotlib theme for all Minecraft PvP Lab charts."""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

BG = "#FAF8F4"
SURFACE = "#FFFFFF"
INK = "#16202A"
MUTED = "#5A6776"
GRID = "#E5E0D8"
BORDER = "#D4CEC3"

TEAL = "#0F766E"       # AI / Champion / Subject
TEAL_LIGHT = "#CCFBF1"
RUST = "#C2410C"       # Opponent / W-tap / Baseline
RUST_LIGHT = "#FFEDD5"
AMBER = "#D97706"      # Highlight / Cue / Ridge / Intermediate
BLUE = "#2563EB"       # Secondary model
SLATE = "#64748B"      # Neutral baseline
SLATE_LIGHT = "#CBD5E1"


def apply_theme():
    plt.rcParams.update({
        "figure.facecolor": BG,
        "axes.facecolor": SURFACE,
        "savefig.facecolor": BG,
        "font.family": "DejaVu Sans",
        "text.color": INK,
        "axes.labelcolor": INK,
        "axes.edgecolor": BORDER,
        "axes.linewidth": 0.9,
        "xtick.color": INK,
        "ytick.color": INK,
        "grid.color": GRID,
        "grid.linewidth": 0.8,
        "legend.frameon": False,
    })


def style_axes(ax, ygrid=True, xgrid=False):
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    ax.spines["left"].set_color(BORDER)
    ax.spines["bottom"].set_color(BORDER)
    ax.set_axisbelow(True)
    if ygrid:
        ax.grid(axis="y", color=GRID, linewidth=0.8)
    if xgrid:
        ax.grid(axis="x", color=GRID, linewidth=0.8)
