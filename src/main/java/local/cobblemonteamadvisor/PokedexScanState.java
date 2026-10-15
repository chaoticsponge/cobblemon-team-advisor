package local.cobblemonteamadvisor;

/** Show once per uninterrupted scan, including repeated server confirmations. */
final class PokedexScanState {
    private Object focus;
    private boolean shown;

    boolean update(Object target, boolean scanning, boolean complete) {
        if (!scanning || target == null) {
            focus = null;
            shown = false;
            return false;
        }
        if (!target.equals(focus)) { focus = target; shown = false; }
        if (!complete || shown) return false;
        shown = true;
        return true;
    }
}
