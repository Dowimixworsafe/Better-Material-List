package com.example.gui;

import com.example.network.BmlClientNetworking;
import com.example.party.PartyManager;
import com.example.party.PlacementSyncHelper;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.resources.Identifier;

@Environment(EnvType.CLIENT)
public class GuiParty extends GuiBase {

    private EditBox inviteField;
    private EditBox syncIdField;
    private final String placementName;
    private static int onlinePlayersPage = 0;

    public GuiParty(String placementName) {
        super();
        this.placementName = placementName;
    }

    public GuiParty() {
        this(null);
    }

    public String getPlacementName() {
        return this.placementName;
    }

    @Override
    public void initGui() {
        super.initGui();

        int startX = (this.width - 460) / 2;
        int startY = 50;
        int leftX = Math.max(10, startX - 220);

        ButtonGeneric btnBack = new ButtonGeneric(leftX, startY - 35, 60, 20, "§c<- Wróć");
        this.addButton(btnBack, (button, mouseButton) -> {
            com.example.input.InputHandler.getInstance().onKeyAction(fi.dy.masa.malilib.hotkeys.KeyAction.PRESS, com.example.config.ModConfig.OPEN_GUI.getKeybind());
        });

        if (this.placementName != null) {
            int tW = this.font.width("Synchronizowane schematy: ");
            this.syncIdField = new EditBox(this.font, startX + tW + 5, startY - 35, 100, 20, Component.literal("Sync ID"));
            this.syncIdField.setValue(com.example.data.SyncIdManager.getSyncId(this.placementName));
            this.syncIdField.setResponder(text -> {
                com.example.data.SyncIdManager.setSyncId(this.placementName, text);
            });
            this.addRenderableWidget(this.syncIdField);
        }

        // Jeśli serwer nie obsługuje BML, pokaż tylko info
        if (!BmlClientNetworking.serverSupported) {
            return;
        }

        int currentY = startY + 40;

        if (PartyManager.isInParty()) {
            List<String> members = PartyManager.getMembers();
            int memBtnY = currentY + 30;
            String selfNick = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getGameProfile().name() : "";

            for (String member : members) {
                if (!member.equals(selfNick)) {
                    if (PartyManager.isAdmin()) {
                        ButtonGeneric btnKick = new ButtonGeneric(startX + 165, memBtnY - 15, 40, 16, "§cKick");
                        this.addButton(btnKick, (button, mouseButton) -> {
                            PartyManager.kickPlayer(member);
                        });

                        ButtonGeneric btnSync = new ButtonGeneric(startX + 120, memBtnY - 15, 40, 16, "Sync");
                        this.addButton(btnSync, (button, mouseButton) -> {
                            PlacementSyncHelper.requestPlacementsFromPlayer(member);
                        });
                    }
                    else {
                        ButtonGeneric btnSync = new ButtonGeneric(startX + 120, memBtnY + 5, 40, 16, "Sync");
                        this.addButton(btnSync, (button, mouseButton) -> {
                            PlacementSyncHelper.requestPlacementsFromPlayer(member);
                        });
                    }
                }
                memBtnY += 20;
            }
            
            currentY = memBtnY + 10;
            String leaveText = PartyManager.isAdmin() ? "§cZamknij Party" : "§cOpuść Party";
            ButtonGeneric btnLeave = new ButtonGeneric(startX, currentY, 100, 20, leaveText);
            this.addButton(btnLeave, (button, mouseButton) -> {
                PartyManager.leaveParty();
                Minecraft.getInstance().setScreen(new GuiParty(this.placementName));
            });

            currentY += 30;

            this.inviteField = new EditBox(this.font, startX, currentY, 120, 20, Component.literal("Nick"));
            this.addRenderableWidget(this.inviteField);

            ButtonGeneric btnInvite = new ButtonGeneric(startX + 130, currentY, 80, 20, "Zaproś");
            this.addButton(btnInvite, (button, mouseButton) -> {
                if (this.inviteField != null && !this.inviteField.getValue().isBlank()) {
                    PartyManager.sendInvite(this.inviteField.getValue());
                    this.inviteField.setValue("");
                }
            });
            
            currentY += 35;
        } else {
            this.inviteField = new EditBox(this.font, startX, currentY, 120, 20, Component.literal("Nick"));
            this.addRenderableWidget(this.inviteField);

            ButtonGeneric btnInvite = new ButtonGeneric(startX + 130, currentY, 120, 20, "Zaproś gracza");
            this.addButton(btnInvite, (button, mouseButton) -> {
                if (this.inviteField != null && !this.inviteField.getValue().isBlank()) {
                    PartyManager.sendInvite(this.inviteField.getValue());
                    Minecraft.getInstance().setScreen(new GuiParty(this.placementName));
                }
            });
            currentY += 40;
        }

        // Przyciski do oczekujących zaproszeń (Lewa kolumna)
        int invY = startY + 10;
        List<PartyManager.PendingInvite> invites = PartyManager.getPendingInvites();
        for (PartyManager.PendingInvite inv : invites) {
            ButtonGeneric btnAccept = new ButtonGeneric(leftX, invY + 15, 60, 20, "§aAkceptuj");
            this.addButton(btnAccept, (button, mouseButton) -> {
                PartyManager.acceptInvite(inv.partyId());
                Minecraft.getInstance().setScreen(new GuiParty(this.placementName));
            });

            ButtonGeneric btnDecline = new ButtonGeneric(leftX + 65, invY + 15, 60, 20, "§cOdrzuć");
            this.addButton(btnDecline, (button, mouseButton) -> {
                PartyManager.declineInvite(inv.partyId());
                Minecraft.getInstance().setScreen(new GuiParty(this.placementName));
            });
            invY += 45;
        }

        // Przyciski Quick Invite (Srodkowa kolumna)
        List<String> recent = PartyManager.getRecentPlayers();
        int recentY = currentY;
        int count = 0;
        for (String nick : recent) {
            if (PartyManager.getMembers().contains(nick)) continue;
            if (count >= 5) break;
            ButtonGeneric btnQuick = new ButtonGeneric(startX + 130, recentY, 60, 20, "Zaproś");
            this.addButton(btnQuick, (button, mouseButton) -> {
                PartyManager.sendInvite(nick);
                Minecraft.getInstance().setScreen(new GuiParty(this.placementName));
            });

            ButtonGeneric btnRemove = new ButtonGeneric(startX + 195, recentY, 20, 20, "§c-");
            this.addButton(btnRemove, (button, mouseButton) -> {
                PartyManager.removeRecentPlayer(nick);
                Minecraft.getInstance().setScreen(new GuiParty(this.placementName));
            });

            recentY += 25;
            count++;
        }

        // Przyciski Graczy Online
        int rightX = startX + 280;
        int rightYLine = startY + 40;
        
        if (Minecraft.getInstance().getConnection() != null) {
            Collection<PlayerInfo> onlinePlayers = Minecraft.getInstance().getConnection().getOnlinePlayers();
            String selfNick = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getGameProfile().name() : "";
            
            List<PlayerInfo> validPlayers = new ArrayList<>();
            for (PlayerInfo info : onlinePlayers) {
                String pName = info.getProfile().name();
                if (!pName.equals(selfNick) && !PartyManager.getMembers().contains(pName)) {
                    validPlayers.add(info);
                }
            }
            
            final int maxPages = Math.max(1, (int) Math.ceil(validPlayers.size() / 10.0));
            if (onlinePlayersPage >= maxPages) onlinePlayersPage = maxPages - 1;
            if (onlinePlayersPage < 0) onlinePlayersPage = 0;
            
            int startIndex = onlinePlayersPage * 10;
            int endIndex = Math.min(startIndex + 10, validPlayers.size());
            
            for (int i = startIndex; i < endIndex; i++) {
                String pName = validPlayers.get(i).getProfile().name();
                ButtonGeneric btnInviteOnline = new ButtonGeneric(rightX + 85, rightYLine - 5, 50, 16, "Zaproś");
                this.addButton(btnInviteOnline, (button, mouseButton) -> {
                    PartyManager.sendInvite(pName);
                    Minecraft.getInstance().setScreen(new GuiParty(this.placementName));
                });
                rightYLine += 25;
            }
            
            if (maxPages > 1) {
                ButtonGeneric btnPrev = new ButtonGeneric(rightX, startY + 295, 20, 20, "<");
                this.addButton(btnPrev, (button, mouseButton) -> {
                    if (onlinePlayersPage > 0) onlinePlayersPage--;
                    Minecraft.getInstance().setScreen(new GuiParty(this.placementName));
                });
                
                ButtonGeneric btnNext = new ButtonGeneric(rightX + 115, startY + 295, 20, 20, ">");
                this.addButton(btnNext, (button, mouseButton) -> {
                    if (onlinePlayersPage < maxPages - 1) onlinePlayersPage++;
                    Minecraft.getInstance().setScreen(new GuiParty(this.placementName));
                });
            }
        }
    }

    @Override
    public void render(GuiGraphics drawContext, int mouseX, int mouseY, float partialTicks) {
        super.render(drawContext, mouseX, mouseY, partialTicks);

        int startX = (this.width - 460) / 2;
        int startY = 40;

        int currentY = startY + 40;

        if (this.syncIdField != null) {
            this.syncIdField.render(drawContext, mouseX, mouseY, partialTicks);
            drawContext.drawString(this.font, "Synchronizowane schematy:", startX, startY - 20, 0xFFFFFFFF, false);
        }

        if (!BmlClientNetworking.serverSupported) {
            drawContext.drawString(this.font, "§cSerwer nie obsługuje BML Party/Sync.", startX, currentY, 0xFFFFFFFF, false);
            drawContext.drawString(this.font, "§7Poproś administratora o zainstalowanie modu lub pluginu BetterMaterialList.", startX, currentY + 15, 0xFFFFFFFF, false);
            return;
        }

        if (PartyManager.isInParty()) {
            String adminNick = PartyManager.getAdminNick();
            String partyName = adminNick != null ? "Party gracza " + adminNick : "Jesteś w Party!";
            drawContext.drawString(this.font, "§a" + partyName, startX, currentY, 0xFFFFFFFF, false);
            drawContext.drawString(this.font, "§7Członkowie:", startX, currentY + 15, 0xFFFFFFFF, false);

            List<String> members = PartyManager.getMembers();
            int memY = currentY + 30;
            for (String member : members) {
                String suffix = (adminNick != null && member.equals(adminNick)) ? " §e(Admin)" : "";
                drawContext.drawString(this.font, " - " + member + suffix, startX, memY, 0xFFFFEAA0, false);
                memY += 20;
            }
            
            currentY = memY + 10 + 30 + 35; // +10 (leave btn Y), +30 (invite field Y), +35 (space after input)
        } else {
            drawContext.drawString(this.font, "§7Nie jesteś w żadnym Party.", startX, currentY, 0xFFFFFFFF, false);
            currentY += 40;
        }

        if (this.inviteField != null) {
            this.inviteField.render(drawContext, mouseX, mouseY, partialTicks);
        }

        // Render zaproszeń (Lewa kolumna)
        int leftX = Math.max(10, startX - 220);
        int invY = startY + 15;
        List<PartyManager.PendingInvite> invites = PartyManager.getPendingInvites();
        if (!invites.isEmpty()) {
            drawContext.drawString(this.font, "§eOczekujące zaproszenia:", leftX, invY - 15, 0xFFFFFFFF, false);
            for (PartyManager.PendingInvite inv : invites) {
                drawContext.drawString(this.font, inv.fromNick(), leftX, invY + 5, 0xFFFFFFFF, false);
                invY += 45;
            }
        }

        // Renderowanie nagłówka Quick Invite i nicków (Srodkowa kolumna)
        List<String> recent = PartyManager.getRecentPlayers();
        int recentY = currentY;
        int count = 0;
        boolean paintedHeader = false;
        for (String nick : recent) {
            if (PartyManager.getMembers().contains(nick)) continue;
            if (count >= 5) break;

            if (!paintedHeader) {
                drawContext.drawString(this.font, "§bOstatnio w Party:", startX, recentY, 0xFFFFFFFF, false);
                paintedHeader = true;
            }
            drawContext.drawString(this.font, nick, startX, recentY + 15, 0xFFFFFFFF, false);
            recentY += 25;
            count++;
        }

        // Renderowanie nagłówka Gracze Online i główek
        int rightX = startX + 280;
        int rightYLine = startY + 40;
        
        if (Minecraft.getInstance().getConnection() != null) {
            Collection<PlayerInfo> onlinePlayers = Minecraft.getInstance().getConnection().getOnlinePlayers();
            String selfNick = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getGameProfile().name() : "";
            
            List<PlayerInfo> validPlayers = new ArrayList<>();
            for (PlayerInfo info : onlinePlayers) {
                String pName = info.getProfile().name();
                if (!pName.equals(selfNick) && !PartyManager.getMembers().contains(pName)) {
                    validPlayers.add(info);
                }
            }
            
            drawContext.drawString(this.font, "§bGracze Online (" + validPlayers.size() + "):", rightX, rightYLine - 15, 0xFFFFFFFF, false);
            
            final int maxPages = Math.max(1, (int) Math.ceil(validPlayers.size() / 10.0));

            int startIndex = onlinePlayersPage * 10;
            int endIndex = Math.min(startIndex + 10, validPlayers.size());
            
            for (int i = startIndex; i < endIndex; i++) {
                PlayerInfo info = validPlayers.get(i);
                String pName = info.getProfile().name();
                
                PlayerFaceRenderer.draw(drawContext, info.getSkin(), rightX, rightYLine - 2, 8);
                
                drawContext.drawString(this.font, pName, rightX + 12, rightYLine - 2, 0xFFFFFFFF, false);
                
                rightYLine += 25;
            }
            
            if (maxPages > 1) {
                String pageStr = (onlinePlayersPage + 1) + " / " + maxPages;
                int strWidth = this.font.width(pageStr);
                drawContext.drawString(this.font, pageStr, rightX + 67 - strWidth / 2, startY + 301, 0xFFFFFFFF, false);
            }
        } else {
            drawContext.drawString(this.font, "§bGracze Online na Serwerze:", rightX, rightYLine - 15, 0xFFFFFFFF, false);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        if (this.inviteField != null && this.inviteField.isFocused()) {
            this.inviteField.setFocused(false);
            return false;
        }
        if (this.syncIdField != null && this.syncIdField.isFocused()) {
            this.syncIdField.setFocused(false);
            return false;
        }
        return super.shouldCloseOnEsc();
    }
}
