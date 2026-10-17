"""Real watchdogs for a bounded number of game ticks; no Minecraft process is launched here."""
import math

def colony_timeout_seconds(max_ticks, tick_rate):
    if max_ticks <= 0 or not math.isfinite(tick_rate) or tick_rate <= 0:
        raise ValueError('Positive game duration and finite positive TPS are required')
    return min(5100, 180 + math.ceil(max_ticks / tick_rate * 3))
