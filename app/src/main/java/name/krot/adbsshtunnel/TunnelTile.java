package name.krot.adbsshtunnel;
import android.service.quicksettings.*;
import com.hardbacknutter.sshd.SshdService;
public final class TunnelTile extends TileService {
    @Override public void onStartListening() { refresh(); }
    private void refresh() {
        Tile tile = getQsTile(); if (tile == null) return;
        tile.setState(SshdService.running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(SshdService.state);
        tile.updateTile();
    }
    @Override public void onClick() { unlockAndRun(() -> { SshdService.select(this, !Settings.enabled(this)); refresh(); }); }
}
