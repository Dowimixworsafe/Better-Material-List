package com.betterlist.gui;

import com.betterlist.input.InputHandler;
import com.betterlist.network.BmlClientNetworking;
import com.betterlist.party.PartyManager;
import com.betterlist.party.PlacementSyncHelper;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.gui.components.PlayerFaceExtractor;

/**
 * Party management GUI. Single-column layout split into sections (Your party /
 * Invites / Invite player / Online players). Button positions (initGui) and labels/
 * heads (extractRenderState) derive from the same constants so they stay aligned.
 */
@Environment(EnvType.CLIENT)
public class GuiParty extends GuiBase {

    private EditBox inviteField;
    private final String placementName;
    private static int onlinePlayersPage = 0;

    // Geometria panelu.
    private static final int PANEL_W = 300;
    private static final int TOP = 44;
    private static final int ROW = 22;       // member/player row height
    private static final int SECTION_GAP = 12;
    private static final int ONLINE_PER_PAGE = 8;

    public GuiParty(String placementName) {
        super();
        this.placementName = placementName;
        this.title = "Better List — Party";
    }

    public GuiParty() {
        this(null);
    }

    public String getPlacementName() {
        return this.placementName;
    }

    private int panelLeft() { return (this.width - PANEL_W) / 2; }

    private List<PlayerInfo> onlineInvitable() {
        List<PlayerInfo> out = new ArrayList<>();
        if (Minecraft.getInstance().getConnection() == null) return out;
        String self = selfNick();
        for (PlayerInfo info : Minecraft.getInstance().getConnection().getOnlinePlayers()) {
            String n = info.getProfile().name();
            if (!n.equals(self) && !PartyManager.getMembers().contains(n)) out.add(info);
        }
        return out;
    }

    private static String selfNick() {
        return Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getGameProfile().name() : "";
    }

    // ── Layout (initGui = buttons + editable fields) ───────────────────────────

    @Override
    public void initGui() {
        super.initGui();
        int left = panelLeft();

        // Back arrow — top-left corner.
        this.addButton(new ButtonGeneric(6, 6, 40, 20, "§e" + com.betterlist.util.BmlLang.tr("bml.gui.back")),
                (b, mb) -> InputHandler.openMaterialList());

        if (!BmlClientNetworking.serverSupported) return;

        int y = TOP;

        // ── Pending invites ──
        List<PartyManager.PendingInvite> invites = PartyManager.getPendingInvites();
        if (!invites.isEmpty()) {
            y += 16; // room for the section header
            for (PartyManager.PendingInvite inv : invites) {
                this.addButton(new ButtonGeneric(left + PANEL_W - 150, y, 70, 20, "§a" + com.betterlist.util.BmlLang.tr("bml.party.accept")),
                        (b, mb) -> { PartyManager.acceptInvite(inv.partyId()); reopen(); });
                this.addButton(new ButtonGeneric(left + PANEL_W - 76, y, 76, 20, "§c" + com.betterlist.util.BmlLang.tr("bml.party.decline")),
                        (b, mb) -> { PartyManager.declineInvite(inv.partyId()); reopen(); });
                y += ROW;
            }
            y += SECTION_GAP;
        }

        // ── Your party (members) ──
        if (PartyManager.isInParty()) {
            y += 16; // "X's Party" header
            List<String> members = PartyManager.getMembers();
            String self = selfNick();
            for (String member : members) {
                if (!member.equals(self)) {
                    // Sync (anyone can request); Kick admin-only.
                    this.addButton(new ButtonGeneric(left + PANEL_W - 120, y, 56, 18, "§b" + com.betterlist.util.BmlLang.tr("bml.party.sync")),
                            (b, mb) -> PlacementSyncHelper.requestPlacementsFromPlayer(member));
                    if (PartyManager.isAdmin()) {
                        this.addButton(new ButtonGeneric(left + PANEL_W - 60, y, 56, 18, "§c" + com.betterlist.util.BmlLang.tr("bml.party.kick")),
                                (b, mb) -> { PartyManager.kickPlayer(member); reopen(); });
                    }
                }
                y += ROW;
            }
            y += SECTION_GAP;

            // Leave / Close.
            String leaveText = PartyManager.isAdmin() ? "§c" + com.betterlist.util.BmlLang.tr("bml.party.close") : "§c" + com.betterlist.util.BmlLang.tr("bml.party.leave");
            this.addButton(new ButtonGeneric(left, y, PANEL_W, 20, leaveText),
                    (b, mb) -> { PartyManager.leaveParty(); reopen(); });
            y += 20 + SECTION_GAP;
        } else {
            // No party — matches the "You are not in a party" header in the render.
            y += 16 + SECTION_GAP;
        }

        // ── Invite player ──
        y += 16; // "Invite player" header
        this.inviteField = new EditBox(this.font, left, y, PANEL_W - 90, 20,
                Component.literal(com.betterlist.util.BmlLang.tr("bml.party.nick_hint")));
        this.addRenderableWidget(this.inviteField);
        this.addButton(new ButtonGeneric(left + PANEL_W - 84, y, 84, 20, "§a" + com.betterlist.util.BmlLang.tr("bml.party.invite")), (b, mb) -> {
            if (this.inviteField != null && !this.inviteField.getValue().isBlank()) {
                PartyManager.sendInvite(this.inviteField.getValue());
                this.inviteField.setValue("");
                reopen();
            }
        });
        y += 20 + SECTION_GAP;

        // ── Online players (paginated) ──
        y += 16; // "Online players (n)" header
        List<PlayerInfo> online = onlineInvitable();
        int maxPages = Math.max(1, (int) Math.ceil(online.size() / (double) ONLINE_PER_PAGE));
        if (onlinePlayersPage >= maxPages) onlinePlayersPage = maxPages - 1;
        if (onlinePlayersPage < 0) onlinePlayersPage = 0;
        int from = onlinePlayersPage * ONLINE_PER_PAGE;
        int to = Math.min(from + ONLINE_PER_PAGE, online.size());
        for (int i = from; i < to; i++) {
            String pName = online.get(i).getProfile().name();
            this.addButton(new ButtonGeneric(left + PANEL_W - 70, y, 70, 18, "§a" + com.betterlist.util.BmlLang.tr("bml.party.invite")),
                    (b, mb) -> { PartyManager.sendInvite(pName); reopen(); });
            y += ROW;
        }
        if (maxPages > 1) {
            this.addButton(new ButtonGeneric(left, y, 28, 20, "§e◄"), (b, mb) -> {
                if (onlinePlayersPage > 0) onlinePlayersPage--;
                reopen();
            });
            this.addButton(new ButtonGeneric(left + PANEL_W - 28, y, 28, 20, "§e►"), (b, mb) -> {
                if (onlinePlayersPage < maxPages - 1) onlinePlayersPage++;
                reopen();
            });
        }
    }

    private void reopen() {
        Minecraft.getInstance().setScreen(new GuiParty(this.placementName));
    }

    // ── Render (text, frames, heads) ────────────────────────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(drawContext, mouseX, mouseY, partialTicks);
        GuiContext ctx = GuiContext.fromGuiGraphics(drawContext);
        int left = panelLeft();

        if (!BmlClientNetworking.serverSupported) {
            ctx.drawString(this.font, "§c" + com.betterlist.util.BmlLang.tr("bml.party.no_server"), left, TOP + 10, 0xFFFFFFFF, false);
            ctx.drawString(this.font, "§7" + com.betterlist.util.BmlLang.tr("bml.party.no_server_hint"), left, TOP + 25, 0xFFFFFFFF, false);
            return;
        }

        int y = TOP;

        // ── Pending invites ──
        List<PartyManager.PendingInvite> invites = PartyManager.getPendingInvites();
        if (!invites.isEmpty()) {
            sectionHeader(ctx, left, y, "§e" + com.betterlist.util.BmlLang.tr("bml.party.pending_invites"));
            y += 16;
            for (PartyManager.PendingInvite inv : invites) {
                rowBg(ctx, left, y);
                ctx.drawString(this.font, "§f" + com.betterlist.util.BmlLang.tr("bml.party.invite_from", "§e" + inv.fromNick()),
                        left + 4, y + 5, 0xFFFFFFFF, false);
                y += ROW;
            }
            y += SECTION_GAP;
        }

        // ── Your party ──
        if (PartyManager.isInParty()) {
            String adminNick = PartyManager.getAdminNick();
            sectionHeader(ctx, left, y, "§a" + (adminNick != null
                    ? com.betterlist.util.BmlLang.tr("bml.party.of_player", "§f" + adminNick)
                    : com.betterlist.util.BmlLang.tr("bml.party.your_party")));
            y += 16;
            String self = selfNick();
            int idx = 0;
            for (String member : PartyManager.getMembers()) {
                rowBg(ctx, left, y);
                // player head (if online)
                PlayerInfo info = Minecraft.getInstance().getConnection() != null
                        ? Minecraft.getInstance().getConnection().getPlayerInfo(member) : null;
                if (info != null)
                    PlayerFaceExtractor.extractRenderState(drawContext, info.getSkin(), left + 3, y + 2, 14);

                boolean isAdmin = adminNick != null && member.equals(adminNick);
                boolean isSelf = member.equals(self);
                String label = (isSelf ? "§b" : "§f") + member
                        + (isAdmin ? " §6" + com.betterlist.util.BmlLang.tr("bml.party.admin") : "")
                        + (isSelf ? " §7" + com.betterlist.util.BmlLang.tr("bml.party.you") : "");
                ctx.drawString(this.font, label, left + 22, y + 5, 0xFFFFFFFF, false);
                y += ROW;
                idx++;
            }
            y += SECTION_GAP;
            // room for the Leave button (drawn in initGui)
            y += 20 + SECTION_GAP;
        } else {
            sectionHeader(ctx, left, y, "§7" + com.betterlist.util.BmlLang.tr("bml.party.not_in_party"));
            y += 16 + SECTION_GAP;
        }

        // ── Invite player ──
        sectionHeader(ctx, left, y, "§a" + com.betterlist.util.BmlLang.tr("bml.party.invite_player"));
        y += 16;
        // The vanilla EditBox isn't auto-rendered inside a malilib GuiBase, so draw a
        // visible field background + the widget itself here.
        if (this.inviteField != null) {
            int fx = this.inviteField.getX(), fy = this.inviteField.getY();
            int fw = this.inviteField.getWidth(), fh = this.inviteField.getHeight();
            ctx.fill(fx - 1, fy - 1, fx + fw + 1, fy + fh + 1, 0xFF000000); // border
            ctx.fill(fx, fy, fx + fw, fy + fh, 0xFF202020);                 // field bg
            this.inviteField.extractWidgetRenderState(drawContext, mouseX, mouseY, partialTicks);
        }
        y += 20 + SECTION_GAP;

        // ── Online players ──
        List<PlayerInfo> online = onlineInvitable();
        int maxPages = Math.max(1, (int) Math.ceil(online.size() / (double) ONLINE_PER_PAGE));
        sectionHeader(ctx, left, y, "§b" + com.betterlist.util.BmlLang.tr("bml.party.online_players", online.size()));
        y += 16;
        int from = onlinePlayersPage * ONLINE_PER_PAGE;
        int to = Math.min(from + ONLINE_PER_PAGE, online.size());
        for (int i = from; i < to; i++) {
            PlayerInfo info = online.get(i);
            rowBg(ctx, left, y);
            PlayerFaceExtractor.extractRenderState(drawContext, info.getSkin(), left + 3, y + 2, 14);
            ctx.drawString(this.font, "§f" + info.getProfile().name(), left + 22, y + 5, 0xFFFFFFFF, false);
            y += ROW;
        }
        if (maxPages > 1) {
            String pageStr = (onlinePlayersPage + 1) + " / " + maxPages;
            int sw = this.font.width(pageStr);
            ctx.drawString(this.font, pageStr, left + (PANEL_W - sw) / 2, y + 6, 0xFFFFFFFF, false);
        }
    }

    private void sectionHeader(GuiContext ctx, int left, int y, String text) {
        ctx.fill(left, y + 11, left + PANEL_W, y + 12, 0x40FFFFFF);
        ctx.drawString(this.font, text, left, y, 0xFFFFFFFF, false);
    }

    private void rowBg(GuiContext ctx, int left, int y) {
        ctx.fill(left, y - 1, left + PANEL_W, y + ROW - 3, 0x18FFFFFF);
    }

    @Override
    protected void drawTitle(GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
        // Centered title at the top — doesn't overlap the corner "Back" button.
        String t = this.getTitleString();
        int x = (this.getScreenWidth() - this.getStringWidth(t)) / 2;
        this.drawString(ctx, t, x, 8, -1);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        if (this.inviteField != null && this.inviteField.isFocused()) {
            this.inviteField.setFocused(false);
            return false;
        }
        // ESC returns to the material list.
        InputHandler.openMaterialList();
        return false;
    }
}
