package handling.handlers;

import client.Client;
import client.character.Char;
import client.character.ExtendSP;
import client.character.commands.AdminCommand;
import client.character.commands.AdminCommands;
import client.character.items.Equip;
import client.character.items.EquipAttribute;
import client.character.items.Inventory;
import client.character.items.Item;
import client.character.skills.*;
import client.field.Field;
import client.field.Portal;
import client.jobs.JobManager;
import client.jobs.adventurer.Archer;
import client.jobs.cygnus.BlazeWizard;
import client.jobs.legend.Luminous;
import client.jobs.sengoku.Kanna;
import client.life.*;
import client.life.movement.Movement;
import connection.InPacket;
import constants.ItemConstants;
import constants.JobConstants;
import constants.SkillConstants;
import enums.*;
import loaders.ItemData;
import loaders.SkillData;
import packet.*;
import server.Channel;
import server.Server;
import server.World;
import util.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static client.character.skills.CharacterTemporaryStat.*;
import static enums.ChatMsgColour.GAME_MESSAGE;
import static enums.ChatMsgColour.YELLOW;
import static enums.EquipBaseStat.cuc;
import static enums.EquipBaseStat.ruc;
import static enums.InvType.EQUIP;
import static enums.InvType.EQUIPPED;
import static enums.InventoryOperation.*;
import static enums.Stat.sp;

/**
 * Created on 12/14/2017.
 */
public class WorldHandler {
    public static void handleCharLogin(Client c, InPacket inPacket) {
        int worldId = inPacket.decodeInt();
        int charId = inPacket.decodeInt();
        Tuple<Byte, Client> info = Server.getInstance().getChannelFromTransfer(charId, worldId);
        byte channel = info.getLeft();
        Server.getInstance().getWorldById(worldId).getChannelById(channel).removeClientFromTransfer(charId);
        c.setChannel(channel);
        c.setChannelInstance(Server.getInstance().getWorldById(worldId).getChannelById(channel));
//        Char chr = Char.getFromDBById(charId);
        Char chr = c.getChr();
        if (chr == null || chr.getId() != charId) {
            chr = Char.getFromDBById(charId);
        }
        chr.setClient(c);
        c.setChr(chr);
        chr.setJobHandler(JobManager.getJobById(chr.getJob(), chr));
        Field field = c.getChannelInstance().getField(chr.getFieldID() <= 0 ? 100000000 : chr.getFieldID());
        field.addChar(chr);
        chr.setField(field);
        c.write(WvsContext.updateEventNameTag(new int[]{}));
        c.write(Stage.setField(chr, field, c.getChannel(), true, 0, true, false,
                (byte) 0, false, 100, null, true, -1));
        if (JobConstants.isBeastTamer(chr.getJob())) {
            c.write(CField.beastTamerFuncKeyMappedManInit());

        } else {
            c.write(CField.funcKeyMappedManInit(chr.getFuncKeyMap()));
        }
        field.spawnLifesForChar(chr);
    }

    public static void handleMove(Client c, InPacket inPacket) {
        // CVecCtrlUser::EndUpdateActive
        byte fieldKey = inPacket.decodeByte();
        inPacket.decodeInt(); // ? something with field
        inPacket.decodeInt(); // tick
        inPacket.decodeByte(); // ? doesn't get set at all
        // CMovePathCommon::Encode
        int encodedGatherDuration = inPacket.decodeInt();
        Position oldPos = inPacket.decodePosition();
        Position oldVPos = inPacket.decodePosition();
        Char chr = c.getChr();
        List<Movement> movements = WvsContext.parseMovement(inPacket);
        for (Movement m : movements) {
            Position pos = m.getPosition();
            chr.setOldPosition(chr.getPosition());
            chr.setPosition(pos);
            chr.setMoveAction(m.getMoveAction());
            chr.setLeft(m.getMoveAction() % 2 == 1);
        }
        chr.getField().checkCharInAffectedAreas(chr);
    }

    public static void handleUserChat(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        inPacket.decodeInt();
        String msg = inPacket.decodeString();
        if (msg.length() > 0 && msg.charAt(0) == '@') {
            if (msg.equalsIgnoreCase("@check")) {
                WvsContext.dispose(c.getChr());
                chr.chatMessage(YELLOW, String.format("X=%d, Y=%d", chr.getPosition().getX(), chr.getPosition().getY()));
            } else if (msg.equalsIgnoreCase("@save")) {
                chr.updateDB();
            }
        } else if (msg.charAt(0) == AdminCommand.getPrefix()) {
            for (Class clazz : AdminCommands.class.getClasses()) {
                if (!(AdminCommand.getPrefix() + clazz.getSimpleName()).equalsIgnoreCase(msg.split(" ")[0])) {
                    continue;
                }
                try {
                    AdminCommand adminCommand = (AdminCommand) clazz.getConstructor().newInstance();
                    Method method = clazz.getDeclaredMethod("execute", Char.class, String[].class);
                    String[] split = msg.split(" ");
                    method.invoke(adminCommand, c.getChr(), split);
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | InstantiationException e) {
                    e.printStackTrace();
                }
            }
        } else {
            chr.getField().broadcastPacket(CField.chat(chr.getId(), ChatType.USER, msg, false, 0, c.getWorldId()));
        }
    }

    public static void handleUserChangeSlotPositionRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        inPacket.decodeInt(); // update tick
        InvType invType = InvType.getInvTypeByVal(inPacket.decodeByte());
        short oldPos = inPacket.decodeShort();
        short newPos = inPacket.decodeShort();
        short quantity = inPacket.decodeShort();
        InvType invTypeFrom = invType == EQUIP ? oldPos < 0 ? EQUIPPED : EQUIP : invType;
        InvType invTypeTo = invType == EQUIP ? newPos < 0 ? EQUIPPED : EQUIP : invType;
        Item item = chr.getInventoryByType(invTypeFrom).getItemBySlot(oldPos);
        if (item == null) {
            chr.dispose();
            return;
        }
        String itemBefore = item.toString();
        if (newPos == 0) { // Drop
            chr.getInventoryByType(invTypeFrom).removeItem(item);
            item.drop();
            Drop drop = new Drop(-1, item);
            chr.getField().drop(drop, chr.getPosition(), chr.getPosition());
            c.write(WvsContext.inventoryOperation(true, false, REMOVE,
                    oldPos, newPos, 0, item));
        } else {
            Item swapItem = chr.getInventoryByType(invTypeTo).getItemBySlot(newPos);
            if (swapItem != null) {
                chr.chatMessage(YELLOW, "SwapItem before: " + swapItem);
            }
            item.setBagIndex(newPos);
            int beforeSizeOn = chr.getEquippedInventory().getItems().size();
            int beforeSize = chr.getEquipInventory().getItems().size();
            if (invType == EQUIP && invTypeFrom != invTypeTo) {
                if (invTypeFrom == EQUIPPED) {
                    chr.unequip(item);
                } else {
                    chr.equip(item);
                    if (swapItem != null) {
                        chr.unequip(swapItem);
                    }
                }
            }
            if (swapItem != null) {
                swapItem.setBagIndex(oldPos);
                chr.chatMessage(YELLOW, "SwapItem after:    " + swapItem);
            }
            int afterSizeOn = chr.getEquippedInventory().getItems().size();
            int afterSize = chr.getEquipInventory().getItems().size();
            if(afterSize + afterSizeOn != beforeSize + beforeSizeOn) {
                throw new RuntimeException("Data duplication!");
            }
            c.write(WvsContext.inventoryOperation(true, false, MOVE, oldPos, newPos,
                    0, item));
            chr.chatMessage(YELLOW, "Item before: " + itemBefore);
            chr.chatMessage(YELLOW, "Item after   : " + item);
            chr.chatMessage(YELLOW, "--");
        }


    }

    public static void handleNonTargetForceAtomAttack(Client c, InPacket inPacket) {
        // fu dan, I actually create a different one for this one as well
        AttackInfo attackInfo = new AttackInfo();
        int skillID2 = inPacket.decodeInt();
        int skillCrc2 = inPacket.decodeInt();
        int ntfaaIdk = inPacket.decodeInt();
        boolean fieldKey = inPacket.decodeByte() == 1;
        byte mask = inPacket.decodeByte();
        byte hits = (byte) (mask & 0xF);
        int mobCount = (mask >>> 4) & 0xF;
        int skillId = inPacket.decodeInt();
        byte slv = inPacket.decodeByte();
        inPacket.decodeByte(); // hardcoded 0
        inPacket.decodeInt(); // crc
        boolean zeroBeta = false;
        if (SkillConstants.isZeroSkill(skillId)) {
            zeroBeta = inPacket.decodeByte() != 0;
        }
        inPacket.decodeByte(); // some zero byte
        inPacket.decodeByte(); // more zero byte
        short maskie = inPacket.decodeShort();
        boolean left = ((maskie >> 15) & 1) != 0;
        short attackAction = (short) (maskie & 0x7FFF);
        inPacket.decodeInt(); // another crc (GETCRC32Svr<long>(&a[*n], 0x405u))
        byte attackActionType = inPacket.decodeByte();
        byte idk0 = 0;
        if (SkillConstants.isEvanForceSkill(skillId)) {
            idk0 = inPacket.decodeByte();
        }
        byte mask2 = inPacket.decodeByte();
        byte attackSpeed = (byte) (mask2 & 0xFFFF);
        byte reduceCount = (byte) (mask2 >>> 4);
        int psdTargetPlus = inPacket.decodeInt();
        int id = inPacket.decodeInt();
        inPacket.decodeInt(); // another zero
        attackInfo.fieldKey = fieldKey;
        attackInfo.hits = hits;
        attackInfo.mobCount = mobCount;
        attackInfo.skillId = skillId;
        attackInfo.slv = slv;
        attackInfo.left = left;
        attackInfo.attackAction = attackAction;
        attackInfo.attackActionType = attackActionType;
        attackInfo.idk0 = idk0;
        attackInfo.attackSpeed = attackSpeed;
        attackInfo.reduceCount = reduceCount;
        attackInfo.psdTargetPlus = psdTargetPlus;
        attackInfo.someId = id;
        for (int i = 0; i < mobCount; i++) {
            MobAttackInfo mai = new MobAttackInfo();
            int mobId = inPacket.decodeInt();
            byte idk1 = inPacket.decodeByte();
            byte idk2 = inPacket.decodeByte();
            byte idk3 = inPacket.decodeByte();
            byte idk4 = inPacket.decodeByte();
            byte idk5 = inPacket.decodeByte();
            int templateID = inPacket.decodeInt();
            byte calcDamageStatIndex = inPacket.decodeByte();
            short rcDstX = inPacket.decodeShort();
            short rectRight = inPacket.decodeShort();
            short oldPosX = inPacket.decodeShort(); // ?
            short oldPosY = inPacket.decodeShort(); // ?
            short sIdk6 = inPacket.decodeShort(); // ?
            short size = attackInfo.hits;
            int[] damages = new int[size];
            for (int j = 0; j < size; j++) {
                damages[j] = inPacket.decodeInt();
            }
            int mobUpDownYRange = inPacket.decodeInt();
            inPacket.decodeInt(); // mob crc
            // Begin PACKETMAKER::MakeAttackInfoPacket
            byte type = inPacket.decodeByte();
            String currentAnimationName = "";
            int animationDeltaL = 0;
            String[] hitPartRunTimes = new String[0];
            if (type == 1) {
                currentAnimationName = inPacket.decodeString();
                animationDeltaL = inPacket.decodeInt();
                int hitPartRunTimesSize = inPacket.decodeInt();
                hitPartRunTimes = new String[hitPartRunTimesSize];
                for (int j = 0; j < hitPartRunTimesSize; j++) {
                    hitPartRunTimes[j] = inPacket.decodeString();
                }
            } else if (type == 2) {
                currentAnimationName = inPacket.decodeString();
                animationDeltaL = inPacket.decodeInt();
            }
            // End PACKETMAKER::MakeAttackInfoPacket
            mai.mobId = mobId;
            mai.idk1 = idk1;
            mai.idk2 = idk2;
            mai.idk3 = idk3;
            mai.idk4 = idk4;
            mai.idk5 = idk5;
            mai.templateID = templateID;
            mai.calcDamageStatIndex = calcDamageStatIndex;
            mai.rcDstX = rcDstX;
            mai.rectRight = rectRight;
            mai.oldPosX = oldPosX;
            mai.oldPosY = oldPosY;
            mai.damages = damages;
            mai.mobUpDownYRange = mobUpDownYRange;
            mai.type = type;
            mai.currentAnimationName = currentAnimationName;
            mai.animationDeltaL = animationDeltaL;
            mai.hitPartRunTimes = hitPartRunTimes;
            attackInfo.mobAttackInfo.add(mai);
        }
        Position somePos = inPacket.decodePosition(); // probably start/end position
        handleAttack(c, attackInfo);
    }

    public static void handleMagicAttack(Client c, InPacket inPacket) {
        AttackInfo attackInfo = new AttackInfo();
        boolean fieldKey = inPacket.decodeByte() == 1;
        byte mask = inPacket.decodeByte();
        byte hits = (byte) (mask & 0xF);
        int mobCount = (mask >>> 4) & 0xF;
        int skillId = inPacket.decodeInt();
        byte slv = inPacket.decodeByte();
        inPacket.decodeInt(); // crc
        int keyDown = -1;
        if (SkillConstants.isKeyDownSkill(skillId)) {
            keyDown = inPacket.decodeInt();
        }
        inPacket.decodeByte(); // some zero byte
        byte idk = inPacket.decodeByte();
        short maskie = inPacket.decodeShort();
        boolean left = ((maskie >> 15) & 1) != 0;
        short attackAction = (short) (maskie & 0x7FFF);
        inPacket.decodeInt(); // another crc (GETCRC32Svr<long>(&a[*n], 0x405u))
        byte attackActionType = inPacket.decodeByte();
        byte idk0 = 0;
        if (SkillConstants.isEvanForceSkill(skillId)) {
            idk0 = inPacket.decodeByte();
        }
        byte mask2 = inPacket.decodeByte();
        byte attackSpeed = (byte) (mask2 & 0xFFFF);
        byte reduceCount = (byte) (mask2 >>> 4);
        int psdTargetPlus = inPacket.decodeInt();
        int id = inPacket.decodeInt();
        attackInfo.fieldKey = fieldKey;
        attackInfo.hits = hits;
        attackInfo.mobCount = mobCount;
        attackInfo.skillId = skillId;
        attackInfo.slv = slv;
        attackInfo.keyDown = keyDown;
        attackInfo.idk = idk;
        attackInfo.left = left;
        attackInfo.attackAction = attackAction;
        attackInfo.attackActionType = attackActionType;
        attackInfo.idk0 = idk0;
        attackInfo.attackSpeed = attackSpeed;
        attackInfo.reduceCount = reduceCount;
        attackInfo.psdTargetPlus = psdTargetPlus;
        attackInfo.someId = id;
        for (int i = 0; i < mobCount; i++) {
            MobAttackInfo mai = new MobAttackInfo();
            int mobId = inPacket.decodeInt();
            byte idk1 = inPacket.decodeByte();
            byte idk2 = inPacket.decodeByte();
            byte idk3 = inPacket.decodeByte();
            byte idk4 = inPacket.decodeByte();
            byte idk5 = inPacket.decodeByte();
            int templateID = inPacket.decodeInt();
            byte calcDamageStatIndex = inPacket.decodeByte();
            short rcDstX = inPacket.decodeShort();
            short rectRight = inPacket.decodeShort();
            short oldPosX = inPacket.decodeShort(); // ?
            short oldPosY = inPacket.decodeShort(); // ?
            short hpPerc = inPacket.decodeByte();
            byte bIdk6;
            short sIdk6;
            if (skillId == 80001835) {
                bIdk6 = inPacket.decodeByte();
            } else {
                sIdk6 = inPacket.decodeShort();
            }
            short size = attackInfo.hits;
            int[] damages = new int[size];
            for (int j = 0; j < size; j++) {
                damages[j] = inPacket.decodeInt();
            }
            int mobUpDownYRange = inPacket.decodeInt();
            inPacket.decodeInt(); // mob crc
            // Begin PACKETMAKER::MakeAttackInfoPacket
            byte type = inPacket.decodeByte();
            String currentAnimationName = "";
            int animationDeltaL = 0;
            String[] hitPartRunTimes = new String[0];
            if (type == 1) {
                currentAnimationName = inPacket.decodeString();
                animationDeltaL = inPacket.decodeInt();
                int hitPartRunTimesSize = inPacket.decodeInt();
                hitPartRunTimes = new String[hitPartRunTimesSize];
                for (int j = 0; j < hitPartRunTimesSize; j++) {
                    hitPartRunTimes[j] = inPacket.decodeString();
                }
            } else if (type == 2) {
                currentAnimationName = inPacket.decodeString();
                animationDeltaL = inPacket.decodeInt();
            }
            // End PACKETMAKER::MakeAttackInfoPacket
            mai.mobId = mobId;
            mai.idk1 = idk1;
            mai.idk2 = idk2;
            mai.idk3 = idk3;
            mai.idk4 = idk4;
            mai.idk5 = idk5;
            mai.templateID = templateID;
            mai.calcDamageStatIndex = calcDamageStatIndex;
            mai.rcDstX = rcDstX;
            mai.rectRight = rectRight;
            mai.oldPosX = oldPosX;
            mai.oldPosY = oldPosY;
            mai.hpPerc = hpPerc;
            mai.damages = damages;
            mai.mobUpDownYRange = mobUpDownYRange;
            mai.type = type;
            mai.currentAnimationName = currentAnimationName;
            mai.animationDeltaL = animationDeltaL;
            mai.hitPartRunTimes = hitPartRunTimes;
            attackInfo.mobAttackInfo.add(mai);
        }
        if (skillId > 27111303) {
            if (skillId == 27121052 || skillId == 80001837) {
                int x = inPacket.decodeShort();
                int y = inPacket.decodeShort();
                attackInfo.x = x;
                attackInfo.y = y;
            }
        } else if (skillId != 32111016) {
            short forcedX = inPacket.decodeShort();
            short forcedY = inPacket.decodeShort();
            boolean dragon = inPacket.decodeByte() != 0;
            attackInfo.forcedX = forcedX;
            attackInfo.forcedY = forcedY;
            if (dragon) {
                short rcDstRight = inPacket.decodeShort();
                short rectRight = inPacket.decodeShort();
                short x = inPacket.decodeShort();
                short y = inPacket.decodeShort();
                inPacket.decodeByte(); // always 0
                inPacket.decodeByte(); // -1
                inPacket.decodeByte(); // 0
                attackInfo.rcDstRight = rcDstRight;
                attackInfo.rectRight = rectRight;
                attackInfo.x = x;
                attackInfo.y = y;
            }
            if (skillId == 12100029) {
                int option = inPacket.decodeInt();
                attackInfo.option = option;
            } else {
                switch (skillId) {
                    case 2121003: // Mist Eruption
                        byte size = inPacket.decodeByte();
                        int[] mists = new int[size];
                        for (int i = 0; i < size; i++) {
                            mists[i] = inPacket.decodeInt();
                        }
                        attackInfo.mists = mists;
                        break;
                    case 2111003: // Poison Mist
                        byte force = inPacket.decodeByte();
                        short forcedXSh = inPacket.decodeShort();
                        short forcedYSh = inPacket.decodeShort();
                        attackInfo.force = force;
                        attackInfo.forcedXSh = forcedXSh;
                        attackInfo.forcedYSh = forcedYSh;
                        break;
                    case 80001835: // Soul Shear, but unreachable?
                        byte sizeB = inPacket.decodeByte();
                        int[] idkArr2 = new int[sizeB];
                        short[] shortArr2 = new short[sizeB];
                        for (int i = 0; i < sizeB; i++) {
                            idkArr2[i] = inPacket.decodeInt();
                            shortArr2[i] = inPacket.decodeShort();
                        }
                        short delay = inPacket.decodeShort();
                        attackInfo.mists = idkArr2;
                        attackInfo.shortArr = shortArr2;
                        attackInfo.delay = delay;
                }
            }
        }
        handleAttack(c, attackInfo);
    }

    private static void handleAttack(Client c, AttackInfo attackInfo) {
        Char chr = c.getChr();
        chr.chatMessage(YELLOW, "SkillID: " + attackInfo.skillId);
        System.out.println("SkillID: " + attackInfo.skillId);
        Field field = c.getChr().getField();
        for (MobAttackInfo mai : attackInfo.mobAttackInfo) {
            Mob mob = (Mob) field.getLifeByObjectID(mai.mobId);
            if (mob == null) {
                chr.chatMessage(ChatMsgColour.CYAN, String.format("Wrong attack info parse (probably)! SkillID = %d, Mob ID = %d", attackInfo.skillId, mai.mobId));
            }
            if (mob != null && mob.getHp() > 0) {
                long totalDamage = Arrays.stream(mai.damages).sum();
                mob.addDamage(chr, totalDamage);
                mob.damage(totalDamage);
            }
        }
        c.getChr().getJobHandler().handleAttack(c, attackInfo);
    }


    public static void handleChangeFieldRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        byte fieldKey = inPacket.decodeByte();
        int idk1 = inPacket.decodeInt();
        int x = inPacket.decodeShort();
        int y = inPacket.decodeShort();
        String portalName = inPacket.decodeString();
        Field field = chr.getField();
        Portal portal = field.getPortalByName(portalName);
        if (portal.getScript() != null && !portal.getScript().equals("")) {
            chr.getScriptManager().startScript(portal.getId(), portal.getScript(), ScriptType.PORTAL);
        } else {
            Field toField = c.getChannelInstance().getField(portal.getTargetMapId());
            chr.warp(toField, portal);
        }
    }

    public static void handleUserPortalScriptRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        byte portalID = inPacket.decodeByte();
        String script = inPacket.decodeString();
        Portal portal = chr.getField().getPortalByID(portalID);
        chr.getScriptManager().startScript(portal.getId(), script, ScriptType.PORTAL);

    }

    public static void handleUserPortalScrollUseRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        inPacket.decodeInt(); //tick
        short slot = inPacket.decodeShort();
        int itemID = inPacket.decodeInt();
        Field field = chr.getField();
        Field toField;
        switch (itemID) {
            case 2030000: //Return to Nearest Town
                toField = c.getChannelInstance().getField(field.getReturnMap());
                break;
            case 2030001: //Return to Lith Harbor
                toField = c.getChannelInstance().getField(104000000);
                break;
            case 2030002: //Return to Ellinia
                toField = c.getChannelInstance().getField(101000000);
                break;
            case 2030003: //Return to Perion
                toField = c.getChannelInstance().getField(102000000);
                break;
            case 2030004: //Return to Henesys
                toField = c.getChannelInstance().getField(100000000);
                break;
            case 2030005: //Return to Kerning City
                toField = c.getChannelInstance().getField(103000000);
                break;
            case 2030006: //Return to Sleepy Wood
                toField = c.getChannelInstance().getField(105000000);
                break;
            case 2030007: //Return to Dead Mine (Map = Ice Valley II)
                toField = c.getChannelInstance().getField(211040200);
                break;
            case 2030019: //Return to Nautilus
                toField = c.getChannelInstance().getField(120000000);
                break;
            case 2030020: //Return to New Leaf City
                toField = c.getChannelInstance().getField(600000000);
                break;
            case 2030025: //Return to Elluel
                toField = c.getChannelInstance().getField(101050000);
                break;
            default:
                toField = c.getChannelInstance().getField(field.getReturnMap());
                System.out.printf("Unhandled Return Scroll: %d in WorldHandler.java%n", itemID);
                break;
        }
        chr.warp(toField);
    }

    public static void handleUserSkillUpRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        inPacket.decodeInt(); // tick
        int skillID = inPacket.decodeInt();
        int amount = inPacket.decodeInt();
        Skill skill = chr.getSkill(skillID, true);
        byte jobLevel = (byte) JobConstants.getJobLevel((short) skill.getRootId());
        Map<Stat, Object> stats = null;
        if (JobConstants.isExtendSpJob(chr.getJob())) {
            ExtendSP esp = chr.getAvatarData().getCharacterStat().getExtendSP();
            int currentSp = esp.getSpByJobLevel(jobLevel);
            if (currentSp >= amount) {
                int curLevel = skill.getCurrentLevel();
                int max = skill.getMaxLevel();
                int newLevel = curLevel + amount > max ? max : curLevel + amount;
                skill.setCurrentLevel(newLevel);
                esp.setSpToJobLevel(jobLevel, currentSp - amount);
                stats = new HashMap<>();
                stats.put(sp, esp);
            }
        } else {
            int currentSp = chr.getAvatarData().getCharacterStat().getSp();
            if (currentSp >= amount) {
                int curLevel = skill.getCurrentLevel();
                int max = skill.getMaxLevel();
                int newLevel = curLevel + amount > max ? max : curLevel + amount;
                skill.setCurrentLevel(newLevel);
                chr.getAvatarData().getCharacterStat().setSp(currentSp - amount);
                stats = new HashMap<>();
                stats.put(sp, chr.getAvatarData().getCharacterStat().getSp());
            }
        }
        if (stats != null) {
            c.write(WvsContext.statChanged(stats));
            List<Skill> skills = new ArrayList<>();
            skills.add(skill);
            chr.addSkill(skill);
            c.write(WvsContext.changeSkillRecordResult(skills, true, false, false, false));
        }
    }

    public static void handleMeleeAttack(Client c, InPacket inPacket) {
        AttackInfo ai = new AttackInfo();
        ai.fieldKey = inPacket.decodeByte() != 0;
        byte mask = inPacket.decodeByte();
        ai.hits = (byte) (mask & 0xF);
        ai.mobCount = (byte) (mask >>> 4);
        ai.skillId = inPacket.decodeInt();
        ai.slv = inPacket.decodeByte();
        ai.addAttackProc = inPacket.decodeByte();
        inPacket.decodeInt(); // crc
        int skillID = ai.skillId;
        if (SkillConstants.isKeyDownSkill(skillID) || SkillConstants.isSuperNovaSkill(skillID)) {
            ai.keyDown = inPacket.decodeInt();
        }
        if (SkillConstants.isRushBombSkill(skillID) || skillID == 5300007 || skillID == 27120211 || skillID == 14111023) {
            ai.grenadeId = inPacket.decodeInt();
        }
        if (SkillConstants.isZeroSkill(skillID)) {
            ai.zero = inPacket.decodeByte();
        }
        if (SkillConstants.isUsercloneSummonedAbleSkill(skillID)) {
            ai.bySummonedID = inPacket.decodeInt();
        }
        byte idk = inPacket.decodeByte();
        ai.idk = inPacket.decodeByte();
        short maskie = inPacket.decodeShort();
        ai.left = ((maskie >> 15) & 1) != 0;
        ai.attackAction = (short) (maskie & 0x7FFF);
        inPacket.decodeInt(); // crc
        ai.attackActionType = inPacket.decodeByte();
        ai.idk0 = inPacket.decodeByte();
        ai.tick = inPacket.decodeInt();
        ai.ptTarget.setY(inPacket.decodeInt());
        ai.finalAttackLastSkillID = inPacket.decodeInt();
        if (ai.finalAttackLastSkillID > 0) {
            ai.finalAttackByte = inPacket.decodeByte();
        }
        if (skillID == 5111009) {
            ai.ignorePCounter = inPacket.decodeByte() != 0;
        }
        /*if ( v1756 )
          {
            COutPacket::Encode2(&a, v1747);
            if ( v674 || is_noconsume_usebullet_melee_attack(v669) )
              COutPacket::Encode4(&a, v1748);
          }*/
        if (skillID == 25111005) {
            ai.spiritCoreEnhance = inPacket.decodeInt();
        }
        for (int i = 0; i < ai.mobCount; i++) {
            MobAttackInfo mai = new MobAttackInfo();
            int mobId = inPacket.decodeInt();
            byte idk1 = inPacket.decodeByte();
            byte idk2 = inPacket.decodeByte();
            byte idk3 = inPacket.decodeByte();
            byte idk4 = inPacket.decodeByte();
            byte idk5 = inPacket.decodeByte();
            int templateID = inPacket.decodeInt();
            byte calcDamageStatIndex = inPacket.decodeByte();
            short rcDstX = inPacket.decodeShort();
            short rectRight = inPacket.decodeShort();
            short idk6 = inPacket.decodeShort();
            short oldPosX = inPacket.decodeShort(); // ?
            short oldPosY = inPacket.decodeShort(); // ?
            int[] damages = new int[ai.hits];
            for (int j = 0; j < ai.hits; j++) {
                damages[j] = inPacket.decodeInt();
            }
            int mobUpDownYRange = inPacket.decodeInt();
            inPacket.decodeInt(); // crc
            boolean isResWarriorLiftPress = false;
            if (skillID == 37111005) {
                isResWarriorLiftPress = inPacket.decodeByte() != 0;
            }
            // Begin PACKETMAKER::MakeAttackInfoPacket
            byte type = inPacket.decodeByte();
            String currentAnimationName = "";
            int animationDeltaL = 0;
            String[] hitPartRunTimes = new String[0];
            if (type == 1) {
                currentAnimationName = inPacket.decodeString();
                animationDeltaL = inPacket.decodeInt();
                int hitPartRunTimesSize = inPacket.decodeInt();
                hitPartRunTimes = new String[hitPartRunTimesSize];
                for (int j = 0; j < hitPartRunTimesSize; j++) {
                    hitPartRunTimes[j] = inPacket.decodeString();
                }
            } else if (type == 2) {
                currentAnimationName = inPacket.decodeString();
                animationDeltaL = inPacket.decodeInt();
            }
            // End PACKETMAKER::MakeAttackInfoPacket
            mai.mobId = mobId;
            mai.idk1 = idk1;
            mai.idk2 = idk2;
            mai.idk3 = idk3;
            mai.idk4 = idk4;
            mai.idk5 = idk5;
            mai.templateID = templateID;
            mai.calcDamageStatIndex = calcDamageStatIndex;
            mai.rcDstX = rcDstX;
            mai.rectRight = rectRight;
            mai.oldPosX = oldPosX;
            mai.oldPosY = oldPosY;
            mai.idk6 = idk6;
            mai.damages = damages;
            mai.mobUpDownYRange = mobUpDownYRange;
            mai.type = type;
            mai.currentAnimationName = currentAnimationName;
            mai.animationDeltaL = animationDeltaL;
            mai.hitPartRunTimes = hitPartRunTimes;
            mai.isResWarriorLiftPress = isResWarriorLiftPress;
            ai.mobAttackInfo.add(mai);
            c.getChr().chatMessage(YELLOW, "atkAction = " + ai.attackAction + ", atkType = " + ai.attackActionType
                    + ", atkCount = " + ai.attackCount + ", idk1 = " + idk1 + ", idk2 = " + idk2 + ", idk3 = " + idk3 + ", idk4 = " + idk4 + ", idk5 = " + idk5);
        }
        if (skillID == 61121052 || skillID == 36121052 || SkillConstants.isScreenCenterAttackSkill(skillID)) {
            ai.ptTarget.setX(inPacket.decodeShort());
            ai.ptTarget.setY(inPacket.decodeShort());
        } else {
            if (SkillConstants.isSuperNovaSkill(skillID)) {
                ai.ptAttackRefPoint.setX(inPacket.decodeShort());
                ai.ptAttackRefPoint.setY(inPacket.decodeShort());
            }
            if (skillID == 101000102) {
                ai.idkPos.setX(inPacket.decodeShort());
                ai.idkPos.setY(inPacket.decodeShort());
            }
            ai.pos.setX(inPacket.decodeShort());
            ai.pos.setY(inPacket.decodeShort());
            if (SkillConstants.isAranFallingStopSkill(skillID)) {
                ai.fh = inPacket.decodeByte();
            }
            if (skillID == 21120019 || skillID == 37121052) {
                ai.teleportPt.setX(inPacket.decodeInt());
                ai.teleportPt.setY(inPacket.decodeInt());
            }
            if (skillID == 61121105 || skillID == 61121222 || skillID == 24121052) {
                ai.Vx = inPacket.decodeShort();
                short x, y;
                for (int i = 0; i < ai.Vx; i++) {
                    x = inPacket.decodeShort();
                    y = inPacket.decodeShort();
                }
            }
            if (skillID == 101120104) {
                // CUser::EncodeAdvancedEarthBreak
                // TODO
            }
            if (skillID == 14111006 && ai.grenadeId != 0) {
                ai.grenadePos.setX(inPacket.decodeShort());
                ai.grenadePos.setY(inPacket.decodeShort());
            }
        }
        c.getChr().chatMessage(YELLOW, "aap = " + ai.addAttackProc);
        handleAttack(c, ai);
    }

    public static void handleBodyAttack(Client c, InPacket inPacket) {
        AttackInfo ai = new AttackInfo();
        ai.fieldKey = inPacket.decodeByte() != 0;
        byte mask = inPacket.decodeByte();
        ai.hits = (byte) (mask & 0xF);
        ai.mobCount = (byte) (mask >>> 4);
        ai.skillId = inPacket.decodeInt();
        ai.slv = inPacket.decodeByte();
        inPacket.decodeInt(); // crc
        ai.areaPAD = inPacket.decodeByte() >>> 3;
        byte nul = inPacket.decodeByte(); // encoded as 0
        ai.left = ((inPacket.decodeShort() >>> 15) & 1) != 0;
        ai.attackCount = inPacket.decodeInt();
        ai.attackSpeed = inPacket.decodeByte(); // encoded as 0
        ai.wt = inPacket.decodeInt();
        ai.ar01Mad = inPacket.decodeInt(); // only done if mage skill
        byte idk2 = inPacket.decodeByte();

        if (ai.skillId > 0) {
            for (int i = 0; i < ai.mobCount; i++) {
                MobAttackInfo mai = new MobAttackInfo();
                mai.mobId = inPacket.decodeInt();
                mai.idkInt = inPacket.decodeInt();
                mai.calcDamageStatIndex = inPacket.decodeByte();
                mai.templateID = inPacket.decodeInt();
                mai.rect = new Rect(inPacket.decodePosition(), inPacket.decodePosition());
                mai.idk6 = inPacket.decodeShort();
                mai.idk1 = inPacket.decodeByte();
                int[] damages = new int[ai.hits];
                for (int j = 0; j < ai.hits; j++) {
                    damages[j] = inPacket.decodeInt();
                }
                mai.damages = damages;
                mai.mobUpDownYRange = inPacket.decodeInt();
                inPacket.decodeInt(); // crc
                // Begin PACKETMAKER::MakeAttackInfoPacket
                byte type = inPacket.decodeByte();
                String currentAnimationName = "";
                int animationDeltaL = 0;
                String[] hitPartRunTimes = new String[0];
                if (type == 1) {
                    currentAnimationName = inPacket.decodeString();
                    animationDeltaL = inPacket.decodeInt();
                    int hitPartRunTimesSize = inPacket.decodeInt();
                    hitPartRunTimes = new String[hitPartRunTimesSize];
                    for (int j = 0; j < hitPartRunTimesSize; j++) {
                        hitPartRunTimes[j] = inPacket.decodeString();
                    }
                } else if (type == 2) {
                    currentAnimationName = inPacket.decodeString();
                    animationDeltaL = inPacket.decodeInt();
                }
                // End PACKETMAKER::MakeAttackInfoPacket
                mai.type = type;
                mai.currentAnimationName = currentAnimationName;
                mai.animationDeltaL = animationDeltaL;
                mai.hitPartRunTimes = hitPartRunTimes;
                ai.mobAttackInfo.add(mai);
            }
        }
        ai.pos = inPacket.decodePosition();
        handleAttack(c, ai);
    }

    public static void handleUserAbilityUpRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        if (chr.getStat(Stat.ap) <= 0) {
            return;
        }
        inPacket.decodeInt(); // tick
        short stat = inPacket.decodeShort();
        Stat charStat = Stat.getByVal(stat);
        short amount = 1;
        if (charStat == Stat.mmp || charStat == Stat.mhp) {
            amount = 20;
        }
        chr.addStat(charStat, amount);
        chr.addStat(Stat.ap, (short) -1);
        Map<Stat, Object> stats = new HashMap<>();
        stats.put(charStat, (short) chr.getStat(charStat));
        stats.put(Stat.ap, (short) chr.getStat(Stat.ap));
        c.write(WvsContext.statChanged(stats));
        WvsContext.dispose(chr);
    }

    public static void handleUserAbilityMassUpRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        inPacket.decodeInt(); // tick
        int type = inPacket.decodeInt();
        Stat charStat = null;
        short amount;
        if (type == 1) {
            charStat = Stat.getByVal(inPacket.decodeShort());
        } else if (type == 2) {
            inPacket.decodeInt();
            inPacket.decodeInt();
            inPacket.decodeInt();
            charStat = Stat.getByVal(inPacket.decodeShort());
        }
        inPacket.decodeInt();
        inPacket.decodeShort();
        amount = inPacket.decodeShort();
        short addStat = amount;
        if (chr.getStat(Stat.ap) < amount) {
            return;
        }
        if (charStat == Stat.mmp || charStat == Stat.mhp) {
            addStat *= 20;
        }
        chr.addStat(charStat, addStat);
        chr.addStat(Stat.ap, (short) -amount);
        Map<Stat, Object> stats = new HashMap<>();
        stats.put(charStat, (short) chr.getStat(charStat));
        stats.put(Stat.ap, (short) chr.getStat(Stat.ap));
        c.write(WvsContext.statChanged(stats));
        WvsContext.dispose(chr);
    }

    public static void handleMobApplyCtrl(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        Field field = chr.getField();
        int mobID = inPacket.decodeInt();
        Mob mob = (Mob) field.getLifeByObjectID(mobID);
        c.write(MobPool.mobChangeController(mob, true, true));
    }

    public static void handleMoveMob(Client c, InPacket inPacket) {
        // CMob::GenerateMovePath (line 918 onwards)
        Field field = c.getChr().getField();
        int objectID = inPacket.decodeInt();
        Life life = field.getLifeByObjectID(objectID);
        if (life == null || !(life instanceof Mob)) {
            return;
        }
        Mob mob = (Mob) life;
        byte idk0 = inPacket.decodeByte(); // check if the templateID / 10000 == 250 or 251. No idea for what it's used
        short moveID = inPacket.decodeShort();
        boolean usedSkill = inPacket.decodeByte() != 0;
        byte lastSkillUsed = inPacket.decodeByte();
        int skillID = 0;
        int slv = 0;
        int idk1 = inPacket.decodeInt();
        if (usedSkill && lastSkillUsed != -1) {
            MobSkill mobSkill = null;
            List<MobSkill> skillList = mob.getSkills();
            if (skillList.size() > 0) {
                if (lastSkillUsed != -1) {
                    mobSkill = skillList.stream()
                            .filter(ms -> ms.getSkillID() == lastSkillUsed).findFirst().orElse(null);
                }
                if (mobSkill == null) {
                    mobSkill = skillList.get(Randomizer.nextInt(skillList.size()));
                }
                skillID = mobSkill.getSkill() != 0 ? mobSkill.getSkill() : mobSkill.getSkill();
                slv = mobSkill.getLevel();
                c.getChr().chatMessage(YELLOW, String.format("Mob did skill with ID = %d, skill = %d, level = %d", mobSkill.getSkillID(), mobSkill.getSkill(), mobSkill.getLevel()));
            }
        }
        byte multiTargetForBallSize = inPacket.decodeByte();
        for (int i = 0; i < multiTargetForBallSize; i++) {
            Position pos = inPacket.decodePosition(); // list of ball positions
        }

        byte randTimeForAreaAttackSize = inPacket.decodeByte();
        for (int i = 0; i < randTimeForAreaAttackSize; i++) {
            short randTimeForAreaAttack = inPacket.decodeShort(); // could be used for cheat detection, but meh
        }

        byte mask = inPacket.decodeByte();
        boolean targetUserIDFromSvr = (mask & 1) != 0;
        boolean isCheatMobMoveRand = ((mask >> 4) & 1) != 0;
        int hackedCode = inPacket.decodeInt();
        int moveAction = inPacket.decodeInt(); // not 100% sure
        int moveActionCS = inPacket.decodeInt();
        int hitExpire = inPacket.decodeInt();
        byte idk = inPacket.decodeByte();
        int encodedGatherDuration = inPacket.decodeInt();
        Position pos = inPacket.decodePosition();
        Position vPos = inPacket.decodePosition();
        Position oldPos = mob.getPosition();
        List<Movement> movements = WvsContext.parseMovement(inPacket);
        if (movements.size() > 0) {
            c.write(MobPool.mobCtrlAck(mob, true, moveID, skillID, (byte) slv, 0));
            field.checkMobInAffectedAreas(mob);
            for (Movement m : movements) {
                Position p = m.getPosition();
                mob.setPosition(p);
                mob.setMoveAction(m.getMoveAction());
                mob.setFh(m.getFh());
            }
        }
    }

    public static void handleUserGrowthRequestHelper(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        Field field = chr.getField();
        short status = inPacket.decodeShort();
        if (status == 0) {
            int mapleGuideMapId = inPacket.decodeInt();
            Field toField = chr.getClient().getChannelInstance().getField(mapleGuideMapId);
            chr.warp(toField);
        }
        if (status == 2) {
            //TODO wtf happens here
            //int write 0
            //int something?
        }

    }

    public static void handleTemporaryStatResetRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        TemporaryStatManager tsm = chr.getTemporaryStatManager();
        int skillId = inPacket.decodeInt();
        tsm.removeStatsBySkill(skillId);
        c.write(WvsContext.temporaryStatReset(chr.getTemporaryStatManager(), false));
    }

    public static void handleKeymapUpdateRequest(Client c, InPacket inPacket) {
        inPacket.decodeInt(); // 0
        int size = inPacket.decodeInt();
        for (int i = 0; i < size; i++) {
            int index = inPacket.decodeInt();
            byte type = inPacket.decodeByte();
            int value = inPacket.decodeInt();
            c.getChr().getFuncKeyMap().putKeyBinding(index, type, value);
        }
    }

    public static void handleSummonedAttack(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        Field field = chr.getField();
        AttackInfo ai = new AttackInfo();
        int summonedID = inPacket.decodeInt();
        ai.summon = (Summon) field.getLifeByObjectID(summonedID);
        ai.updateTime = inPacket.decodeInt();
        ai.skillId = inPacket.decodeInt();
        int nul = inPacket.decodeInt();
        byte maskIdk = inPacket.decodeByte();
        byte idk = (byte) (maskIdk & 0x7F);
        byte idk2 = (byte) (maskIdk >>> 8);
        byte mask = inPacket.decodeByte();
        ai.hits = (byte) (mask & 0xF);
        ai.mobCount = (byte) (mask >>> 4);
        byte nul2 = inPacket.decodeByte();
        ai.attackAction = inPacket.decodeShort();
        ai.attackCount = inPacket.decodeShort();
        ai.pos = inPacket.decodePosition();
        int minOne = inPacket.decodeInt();
        short idk3 = inPacket.decodeShort();
        int idk4 = inPacket.decodeInt();
        int nul3 = inPacket.decodeInt();
        ai.bulletID = inPacket.decodeInt();
        for (int i = 0; i < ai.mobCount; i++) {
            MobAttackInfo mai = new MobAttackInfo();
            mai.mobId = inPacket.decodeInt();
            mai.templateID = inPacket.decodeInt();
            byte byteIdk1 = inPacket.decodeByte();
            byte byteIdk2 = inPacket.decodeByte();
            byte byteIdk3 = inPacket.decodeByte();
            byte byteIdk4 = inPacket.decodeByte();
            byte byteIdk5 = inPacket.decodeByte();
            int idk5 = inPacket.decodeInt(); // another template id, same as the one above
            byte byteIdk6 = inPacket.decodeByte();
            mai.rect = inPacket.decodeShortRect();
            short idk6 = inPacket.decodeShort();
            int[] damages = new int[ai.hits];
            for (int j = 0; j < ai.hits; j++) {
                damages[j] = inPacket.decodeInt();
            }
            mai.damages = damages;
            mai.mobUpDownYRange = inPacket.decodeInt();
//            inPacket.decodeInt(); // crc
            // Begin PACKETMAKER::MakeAttackInfoPacket
            byte type = inPacket.decodeByte();
            String currentAnimationName = "";
            int animationDeltaL = 0;
            String[] hitPartRunTimes = new String[0];
            if (type == 1) {
                currentAnimationName = inPacket.decodeString();
                animationDeltaL = inPacket.decodeInt();
                int hitPartRunTimesSize = inPacket.decodeInt();
                hitPartRunTimes = new String[hitPartRunTimesSize];
                for (int j = 0; j < hitPartRunTimesSize; j++) {
                    hitPartRunTimes[j] = inPacket.decodeString();
                }
            } else if (type == 2) {
                currentAnimationName = inPacket.decodeString();
                animationDeltaL = inPacket.decodeInt();
            }
            // End PACKETMAKER::MakeAttackInfoPacket
            mai.type = type;
            mai.currentAnimationName = currentAnimationName;
            mai.animationDeltaL = animationDeltaL;
            mai.hitPartRunTimes = hitPartRunTimes;
            ai.mobAttackInfo.add(mai);
        }
        handleAttack(c, ai);
    }

    public static void handleShootAttack(Client c, InPacket inPacket) {
        AttackInfo ai = new AttackInfo();
        byte nul = inPacket.decodeByte();
        ai.fieldKey = inPacket.decodeByte() != 0;
        byte mask = inPacket.decodeByte();
        ai.hits = (byte) (mask & 0xF);
        ai.mobCount = (byte) (mask >>> 4);
        ai.skillId = inPacket.decodeInt();
        ai.slv = inPacket.decodeByte();
        ai.addAttackProc = inPacket.decodeByte();
        c.getChr().chatMessage(YELLOW, "addAttackProc: " + ai.addAttackProc);
        inPacket.decodeInt(); // crc
        int skillID = ai.skillId;
        if (SkillConstants.isKeyDownSkill(skillID) || SkillConstants.isSuperNovaSkill(skillID)) {
            ai.keyDown = inPacket.decodeInt();
        }
        if (SkillConstants.isZeroSkill(skillID)) {
            ai.zero = inPacket.decodeByte();
        }
        if (SkillConstants.isUsercloneSummonedAbleSkill(skillID)) {
            ai.bySummonedID = inPacket.decodeInt();
        }
        byte idk = inPacket.decodeByte();
        byte idk2 = inPacket.decodeByte();
        int idk3 = inPacket.decodeInt();
        ai.isJablin = inPacket.decodeByte() != 0;
        short maskie = inPacket.decodeShort();
        ai.left = ((maskie >> 15) & 1) != 0;
        ai.attackAction = (short) (maskie & 0x7FFF);
        if (skillID == Archer.ARROW_PLATTER) { // very unsure
            short idk9 = inPacket.decodeShort();
            int idk10 = inPacket.decodeInt();
            short idk11 = inPacket.decodeShort();
        }
        int idk4 = inPacket.decodeInt();
        ai.attackActionType = inPacket.decodeByte();
        if (skillID == 23111001 || skillID == 80001915 || skillID == 36111010) {
            int idk5 = inPacket.decodeInt();
            int x = inPacket.decodeInt();
            int y = inPacket.decodeInt();
        }
        ai.attackSpeed = inPacket.decodeByte();
        int idk6 = inPacket.decodeInt();
        int idk7 = inPacket.decodeInt();
        short idk8 = inPacket.decodeShort();
        ai.attackAction = inPacket.decodeShort();
        ai.attackActionType = inPacket.decodeByte();
        if (!SkillConstants.isShootSkillNotConsumingBullets(skillID)) {
            ai.bulletCount = inPacket.decodeInt();
        }
        ai.rect = inPacket.decodeShortRect();
        for (int i = 0; i < ai.mobCount; i++) {
            MobAttackInfo mai = new MobAttackInfo();
            mai.mobId = inPacket.decodeInt();
            byte byteIdk1 = inPacket.decodeByte();
            byte byteIdk2 = inPacket.decodeByte();
            byte byteIdk3 = inPacket.decodeByte();
            byte byteIdk4 = inPacket.decodeByte();
            byte byteIdk5 = inPacket.decodeByte();
            mai.templateID = inPacket.decodeInt();
            mai.calcDamageStatIndex = (byte) (inPacket.decodeByte() & 0x7F);
            mai.rect = inPacket.decodeShortRect();
            short idk1 = inPacket.decodeShort();
            int[] damages = new int[ai.hits];
            for (int j = 0; j < ai.hits; j++) {
                damages[j] = inPacket.decodeInt();
            }
            mai.damages = damages;
            mai.mobUpDownYRange = inPacket.decodeInt();
            inPacket.decodeInt(); // crc
            // Begin PACKETMAKER::MakeAttackInfoPacket
            byte type = inPacket.decodeByte();
            String currentAnimationName = "";
            int animationDeltaL = 0;
            String[] hitPartRunTimes = new String[0];
            if (type == 1) {
                currentAnimationName = inPacket.decodeString();
                animationDeltaL = inPacket.decodeInt();
                int hitPartRunTimesSize = inPacket.decodeInt();
                hitPartRunTimes = new String[hitPartRunTimesSize];
                for (int j = 0; j < hitPartRunTimesSize; j++) {
                    hitPartRunTimes[j] = inPacket.decodeString();
                }
            } else if (type == 2) {
                currentAnimationName = inPacket.decodeString();
                animationDeltaL = inPacket.decodeInt();
            }
            // End PACKETMAKER::MakeAttackInfoPacket
            mai.type = type;
            mai.currentAnimationName = currentAnimationName;
            mai.animationDeltaL = animationDeltaL;
            mai.hitPartRunTimes = hitPartRunTimes;
            ai.mobAttackInfo.add(mai);
            if (SkillConstants.isScreenCenterAttackSkill(skillID)) {
                ai.forcedX = inPacket.decodeShort();
                ai.forcedY = inPacket.decodeShort();
            }
            if (false) {
                // not called?
                ai.idkPos = inPacket.decodePosition();
            }
            if (SkillConstants.isAranFallingStopSkill(skillID)) {
                ai.fh = inPacket.decodeByte();
            }
            if (skillID > 0) {
                if (SkillData.getSkillInfoById(skillID).getRootId() / 100 == 33) {
                    ai.bodyRelMove = inPacket.decodePosition();
                }
            }
            if (SkillConstants.isKeydownSkillRectMoveXY(skillID)) {
                ai.keyDownRectMoveXY = inPacket.decodePosition();
            }
            if (skillID == 23121002 || skillID == 80001914) {
                ai.fh = inPacket.decodeByte();
            }
        }
        handleAttack(c, ai);
    }

    public static void handleChangeChannelRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        if (c.getAccount() != null) {
            c.getAccount().updateDB();
        }
        chr.updateDB();
        int worldID = chr.getClient().getChannelInstance().getWorldId();
        World world = Server.getInstance().getWorldById(worldID);
        Field field = chr.getField();
        field.removeChar(chr);
        byte channelID = (byte) (inPacket.decodeByte() + 1);
        Channel channel = world.getChannelById(channelID);
        channel.addClientInTransfer(channelID, chr.getId(), c);
        short port = (short) channel.getPort();
        c.write(ClientSocket.migrateCommand(true, port));
    }

    public static void handleUserChangeStatRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        inPacket.decodeInt(); // tick
        int mask = inPacket.decodeInt();
        List<Stat> stats = Stat.getStatsByFlag(mask); // should be in correct order
        inPacket.decodeInt();
        HashMap hashMap = new HashMap();
        for (Stat stat : stats) {
            hashMap.put(stat, inPacket.decodeShort()); // always short?
        }
        Map<Stat, Object> newStats = new HashMap<>();
        byte option = inPacket.decodeByte();
        if (hashMap.containsKey(Stat.hp)) {
            int curHP = chr.getStat(Stat.hp);
            int maxHP = chr.getStat(Stat.mhp);
            short extra = (short) hashMap.get(Stat.hp);
            int newHP = curHP + extra > maxHP ? maxHP : curHP + extra;
            chr.setStat(Stat.hp, newHP);
            newStats.put(Stat.hp, newHP);
        } else if (hashMap.containsKey(Stat.mp)) {
            int curMP = chr.getStat(Stat.mp);
            int maxMP = chr.getStat(Stat.mmp);
            short extra = (short) hashMap.get(Stat.mp);
            int newMP = curMP + extra > maxMP ? maxMP : curMP + extra;
            chr.setStat(Stat.mp, newMP);
            newStats.put(Stat.mp, newMP);
        }

        c.write(WvsContext.statChanged(newStats));
    }

    public static void handleCreateKinesisPsychicArea(Client c, InPacket inPacket) {
        PsychicArea pa = new PsychicArea();
        pa.action = inPacket.decodeInt();
        pa.actionSpeed = inPacket.decodeInt();
        pa.localPsychicAreaKey = inPacket.decodeInt();
        pa.psychicAreaKey = inPacket.decodeInt();
        pa.skillID = inPacket.decodeInt();
        pa.slv = inPacket.decodeShort();
        pa.duration = inPacket.decodeInt();
        pa.isLeft = inPacket.decodeByte() != 0;
        pa.skeletonFilePathIdx = inPacket.decodeShort();
        pa.skeletonAniIdx = inPacket.decodeShort();
        pa.skeletonLoop = inPacket.decodeShort();
        pa.start = inPacket.decodePositionInt();
        c.write(CField.createPsychicArea(true, pa));
//        AffectedArea aa = new AffectedArea(-1);
//        aa.setSkillID(pa.skillID);
//        aa.setSlv((byte) pa.slv);
//        aa.setMobOrigin((byte) 0);
//        aa.setCharID(c.getChr().getId());
//        int x = pa.start.getX();
//        int y = pa.start.getY();
//        aa.setPosition(new Position(x, y));
//        aa.setFlip(pa.isLeft);
//        aa.setElemAttr(1);
//        aa.setOption(1);
//        SkillInfo si = SkillData.getSkillInfoById(pa.skillID);
//        aa.setRect(aa.getPosition().getRectAround(si.getRects().get(0)));
//        c.getChr().getField().spawnAffectedArea(aa);
    }

    public static void handleReleasePsychicArea(Client c, InPacket inPacket) {
        int localPsychicAreaKey = inPacket.decodeInt();
        c.write(CField.releasePsychicArea(localPsychicAreaKey));
    }

    public static void handleCreatePsychicLock(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        Field f = chr.getField();
        PsychicLock pl = new PsychicLock();
        pl.skillID = inPacket.decodeInt();
        pl.slv = inPacket.decodeShort();
        pl.action = inPacket.decodeInt();
        pl.actionSpeed = inPacket.decodeInt();
        while (inPacket.decodeByte() != 0) {
            PsychicLockBall plb = new PsychicLockBall();
            plb.localKey = inPacket.decodeInt();
            plb.psychicLockKey = inPacket.decodeInt();
            int mobID = inPacket.decodeInt();
            plb.mob = (Mob) f.getLifeByObjectID(mobID);
            plb.stuffID = inPacket.decodeShort();
            plb.usableCount = inPacket.decodeShort();
            plb.posRelID = inPacket.decodeByte();
            plb.start = inPacket.decodePositionInt();
            plb.rel = inPacket.decodePositionInt();
        }
        // TODO can't attack after this, gotta fix
    }

    public static void handleReleasePsychicLock(Client c, InPacket inPacket) {
        int skillID = inPacket.decodeInt();
        short slv = inPacket.decodeShort();
        short count = inPacket.decodeShort();
        int id = inPacket.decodeInt();
        int mobID = inPacket.decodeInt();
        if (mobID != 0) {
            List<Integer> l = new ArrayList<>();
            l.add(mobID);
            c.write(CField.releasePsychicLockMob(l));
        } else {
            c.write(CField.releasePsychicLock(id));
        }
    }

    public static void handleSummonedRemove(Client c, InPacket inPacket) {
        int id = inPacket.decodeInt();
        c.getChr().getField().removeLife(id, false);
    }

    public static void handleForceAtomCollision(Client c, InPacket inPacket) {
        int size = inPacket.decodeInt();
        int idk2 = inPacket.decodeInt();
        for (int i = 0; i < size; i++) {
            int idk3 = inPacket.decodeInt();
            byte idk4 = inPacket.decodeByte();
            int mobID = inPacket.decodeInt();
            Mob mob = (Mob) c.getChr().getField().getLifeByObjectID(mobID);
            if (mob != null) {
//            mob.damage((long) 133337);
//            c.write(CField.mobDamaged(mobID, (long) 133337, mob.getTemplateId(), (byte) 1, (int) mob.getHp(), (int) mob.getMaxHp()));
            }
        }
    }

    public static void handleRequestArrowPlatterObj(Client c, InPacket inPacket) {

    }

    public static void handleUserCharacterInfoRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        Field field = chr.getField();
        inPacket.decodeInt(); // tick
        int requestID = inPacket.decodeInt();
        Char requestChar = field.getCharByID(requestID);
        if (requestChar == null) {
            chr.chatMessage(GAME_MESSAGE, "The character you tried to find could not be found.");
        } else {
            c.write(CField.characterInfo(requestChar));
        }
    }

    public static void handleSummonedHit(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        Field field = chr.getField();
        int id = inPacket.decodeInt();
        Life life = field.getLifeByObjectID(id);
        if (life == null || !(life instanceof Summon)) {
            return;
        }
    }

    public static void handleUserFlameOrbRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        int skillID = inPacket.decodeInt();
        byte slv = inPacket.decodeByte();
        short dir = inPacket.decodeShort();
        SkillInfo si = SkillData.getSkillInfoById(skillID);
        int range = si.getValue(SkillStat.range, slv);
        ForceAtomEnum fae;
        switch (skillID) {
            case BlazeWizard.FINAL_ORBITAL_FLAME:
                fae = ForceAtomEnum.ORBITAL_FLAME_4;
                skillID = BlazeWizard.FINAL_ORBITAL_FLAME_ATOM;
                break;
            case BlazeWizard.GRAND_ORBITAL_FLAME:
                fae = ForceAtomEnum.ORBITAL_FLAME_3;
                skillID = BlazeWizard.GRAND_ORBITAL_FLAME_ATOM;
                break;
            case BlazeWizard.GREATER_ORBITAL_FLAME:
                fae = ForceAtomEnum.ORBITAL_FLAME_2;
                skillID = BlazeWizard.GREATER_ORBITAL_FLAME_ATOM;
                break;
            default:
                fae = ForceAtomEnum.ORBITAL_FLAME_1;
                skillID = BlazeWizard.ORBITAL_FLAME_ATOM;
                break;
        }
        int curTime = Util.getCurrentTime();
        int angle = 0;
        switch (dir) {
            case 1:
                angle = 180;
                break;
            case 2:
                angle = 270;
                break;
            case 3:
                angle = 90;
                break;
        }
        ForceAtomInfo fai = new ForceAtomInfo(1, fae.getInc(), 11, 13,
                angle, 0, curTime, si.getValue(SkillStat.mobCount, slv), skillID, new Position(0, 0));
        List<ForceAtomInfo> faiList = new ArrayList<>();
        faiList.add(fai);
        c.write(CField.createForceAtom(false, 0, chr.getId(), fae.getForceAtomType(), false,
                new ArrayList<>(), skillID, faiList, null, dir, range, null, 0, null));
    }

    public static void handleUserConsumeCashItemUseRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        Inventory cashInv = chr.getInventoryByType(InvType.CASH);
        inPacket.decodeInt(); // tick
        short pos = inPacket.decodeShort();
        int itemID = inPacket.decodeInt();
        Item item = cashInv.getItemBySlot(pos);
        if (item == null || item.getItemId() != itemID) {
            return;
        }
        switch (itemID) {
            case 5040004: // Hyper Teleport Rock
                short idk = inPacket.decodeShort();
                int mapID = inPacket.decodeInt();
                Field field = c.getChannelInstance().getField(mapID);
                chr.warp(field);
                break;
            case 5062009: // Red Cube
                short ePos = (short) inPacket.decodeInt();
                InvType invType = ePos < 0 ? EQUIPPED : EQUIP;
                Equip equip = (Equip) chr.getInventoryByType(invType).getItemBySlot(ePos);
                if (equip == null) {
                    chr.chatMessage(GAME_MESSAGE, "Could not find equip.");
                    return;
                }
                int tierUpChance = ItemConstants.getTierUpChance(itemID);
                short hiddenValue = ItemGrade.getHiddenGradeByVal(equip.getBaseGrade()).getVal();
                boolean tierUp = !(hiddenValue >= ItemGrade.HIDDEN_LEGENDARY.getVal()) && Util.succeedProp(tierUpChance);
                if (tierUp) {
                    hiddenValue++;
                }
                equip.setHiddenOptionBase(hiddenValue, ItemConstants.THIRD_LINE_CHANCE);
                equip.releaseOptions(false);
                c.write(CField.redCubeResult(chr.getId(), tierUp, itemID, ePos, equip));
                c.write(CField.showItemReleaseEffect(chr.getId(), ePos, false));
                c.write(WvsContext.inventoryOperation(true, false, ADD, ePos, (short) 0,
                        0, equip));
                chr.consumeItem(item);
                break;
            case 5062500: // Bonus potential cube
                ePos = (short) inPacket.decodeInt();
                invType = ePos < 0 ? EQUIPPED : EQUIP;
                equip = (Equip) chr.getInventoryByType(invType).getItemBySlot(ePos);
                if (equip == null) {
                    chr.chatMessage(GAME_MESSAGE, "Could not find equip.");
                    return;
                }
                tierUpChance = ItemConstants.getTierUpChance(itemID);
                hiddenValue = ItemGrade.getHiddenGradeByVal(equip.getBonusGrade()).getVal();
                tierUp = !(hiddenValue >= ItemGrade.HIDDEN_LEGENDARY.getVal()) && Util.succeedProp(tierUpChance);
                if (tierUp) {
                    hiddenValue++;
                }
                equip.setHiddenOptionBonus(hiddenValue, ItemConstants.THIRD_LINE_CHANCE);
                equip.releaseOptions(true);
                c.write(CField.inGameCubeResult(chr.getId(), tierUp, itemID, ePos, equip));
                c.write(CField.showItemReleaseEffect(chr.getId(), ePos, false));
                c.write(WvsContext.inventoryOperation(true, false, ADD, ePos, (short) 0,
                        0, equip));
                chr.consumeItem(item);
                break;
            default:
                chr.chatMessage(YELLOW, "Cash item " + itemID + " is not implemented, notify Sjonnie pls.");
                chr.dispose();
                break;
        }
    }

    public static void handleUserFinalAttackRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        int skillID = inPacket.decodeInt();
        int pSkill = inPacket.decodeInt();
        int targetID = inPacket.decodeInt();
        int requestTime = inPacket.decodeInt();
        c.write(CField.finalAttackRequest(chr, skillID, chr.getJobHandler().getFinalAttackSkill(), 0, targetID, requestTime));
    }

    public static void handleUserUpgradeAssistItemUseRequest(Client c, InPacket inPacket) {

        Char chr = c.getChr();
        inPacket.decodeInt(); //tick
        short uPos = inPacket.decodeShort(); //Use Position
        short ePos = inPacket.decodeShort(); //Eqp Position
        byte bEnchantSkill = inPacket.decodeByte(); //no clue what this means exactly
//        short idk = inPacket.decodeShort(); //No clue what this is, stayed  00 00  throughout different tests
        Item scroll = chr.getInventoryByType(InvType.CONSUME).getItemBySlot(uPos);
        InvType invType = ePos < 0 ? EQUIPPED : EQUIP;
        Equip equip = (Equip) chr.getInventoryByType(invType).getItemBySlot(ePos);
        if (scroll == null || equip == null) {
            chr.chatMessage(GAME_MESSAGE, "Could not find scroll or equip.");
            return;
        }
        int scrollID = scroll.getItemId();
        switch (scrollID) {
            case 2532000: // Safety Scroll
            case 2532001: // Pet Safety Scroll
            case 2532002: // Safety Scroll
            case 2532003: // Safety Scroll
            case 2532004: // Pet Safety Scroll
            case 2532005: // Safety Scroll
                equip.addAttribute(EquipAttribute.UPGRADE_COUNT_PROTECTION);
                break;
            case 2530000: // Lucky Day
            case 2530002: // Lucky Day
            case 2530003: // Pet Lucky Day
            case 2530004: // Lucky Day
            case 2530006: // Pet Lucky Day
                equip.addAttribute(EquipAttribute.LUCKY_DAY);
                break;
            case 2531000: // Protection Scroll
            case 2531001:
            case 2531004:
            case 2531005:
                equip.addAttribute(EquipAttribute.PROTECTION_SCROLL);
                break;
        }
        c.write(CField.showItemUpgradeEffect(chr.getId(), true, false, scrollID, equip.getItemId()));
        c.write(WvsContext.inventoryOperation(true, false, ADD, ePos, (short) 0,
                0, equip));
        chr.consumeItem(scroll);
    }

    public static void handleUserUpgradeItemUseRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        inPacket.decodeInt(); //tick
        short uPos = inPacket.decodeShort(); //Use Position
        short ePos = inPacket.decodeShort(); //Eqp Position
        byte bEnchantSkill = inPacket.decodeByte(); //no clue what this means exactly
        short idk = inPacket.decodeShort(); //No clue what this is, stayed  00 00  throughout different tests
        Item scroll = chr.getInventoryByType(InvType.CONSUME).getItemBySlot(uPos);
        InvType invType = ePos < 0 ? EQUIPPED : EQUIP;
        Equip equip = (Equip) chr.getInventoryByType(invType).getItemBySlot(ePos);
        if (scroll == null || equip == null) {
            chr.chatMessage(GAME_MESSAGE, "Could not find scroll or equip.");
            return;
        }
        int scrollID = scroll.getItemId();
        boolean success = true;
        Map<ScrollStat, Integer> vals = ItemData.getItemInfoByID(scrollID).getScrollStats();
        if (vals.size() > 0) {
            if (equip.getRuc() <= 0) {
                WvsContext.dispose(chr);
                return;
            }
            int chance = vals.getOrDefault(ScrollStat.success, 100);
            int curse = vals.getOrDefault(ScrollStat.cursed, 0);
            success = Util.succeedProp(chance);
            if (success) {
                boolean chaos = vals.containsKey(ScrollStat.randStat) || vals.containsKey(ScrollStat.noNegative);
                if (chaos) {
                    boolean noNegative = vals.containsKey(ScrollStat.noNegative);
                    int max = 5;
                    switch (scrollID) {
                        case 2049129: // Chaos Scroll of Goodness
                        case 2049130:
                        case 2049131:
                        case 2049132:
                            max = 3;
                            break;
                    }
                    for (EquipBaseStat ebs : ScrollStat.getRandStats()) {
                        int cur = (int) equip.getBaseStat(ebs);
                        if (cur == 0) {
                            continue;
                        }
                        int randStat = Util.getRandom(max);
                        randStat = !noNegative && Util.succeedProp(50) ? -randStat : randStat;
                        equip.addStat(ebs, randStat);
                    }
                } else {
                    for (Map.Entry<ScrollStat, Integer> entry : vals.entrySet()) {
                        ScrollStat ss = entry.getKey();
                        int val = entry.getValue();
                        if (ss.getEquipStat() != null) {
                            equip.addStat(ss.getEquipStat(), val);
                        }
                    }
                }
                equip.addStat(ruc, -1);
                equip.addStat(cuc, 1);
            } else {
                if (!equip.hasAttribute(EquipAttribute.UPGRADE_COUNT_PROTECTION)) {
                    equip.addStat(ruc, -1);
                } else {
                    equip.removeAttribute(EquipAttribute.UPGRADE_COUNT_PROTECTION);
                }
            }
        }
        c.write(CField.showItemUpgradeEffect(chr.getId(), success, false, scrollID, equip.getItemId()));
        c.write(WvsContext.inventoryOperation(true, false, ADD, ePos, (short) 0,
                0, equip));
        chr.consumeItem(scroll);

    }

    public static void handleUserItemOptionUpgradeItemUseRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        inPacket.decodeInt(); //tick
        short uPos = inPacket.decodeShort();
        short ePos = inPacket.decodeShort();
        byte bEnchantSkill = inPacket.decodeByte();
        Item scroll = chr.getInventoryByType(InvType.CONSUME).getItemBySlot(uPos);
        InvType invType = ePos < 0 ? EQUIPPED : EQUIP;
        Equip equip = (Equip) chr.getInventoryByType(invType).getItemBySlot(ePos);
        if (scroll == null || equip == null) {
            chr.chatMessage(GAME_MESSAGE, "Could not find scroll or equip.");
            return;
        }
        int scrollID = scroll.getItemId();
        boolean success = true;
        Map<ScrollStat, Integer> vals = ItemData.getItemInfoByID(scrollID).getScrollStats();
        int chance = vals.getOrDefault(ScrollStat.success, 100);
        int curse = vals.getOrDefault(ScrollStat.cursed, 0);
        success = Util.succeedProp(chance);
        if (success) {
            short val;
            int thirdLineChance = ItemConstants.THIRD_LINE_CHANCE;
            switch (scrollID) {
                case 2049400: // Rare Pot
                case 2049401:
                case 2049402:
                case 2049403:
                case 2049404:
                case 2049405:
                case 2049406:
                case 2049407:
                case 2049408:
                case 2049412:
                case 2049413:
                case 2049414:
                case 2049415:
                case 2049416:
                case 2049417:
                case 2049418:
                case 2049419:
                    val = ItemGrade.HIDDEN_RARE.getVal();
                    equip.setHiddenOptionBase(val, thirdLineChance);
                    break;
                case 0:
                    val = ItemGrade.HIDDEN_EPIC.getVal();
                    equip.setHiddenOptionBase(val, thirdLineChance);
                case 2049762: // Unique Pot
                case 2049764:
                    val = ItemGrade.HIDDEN_UNIQUE.getVal();
                    equip.setHiddenOptionBase(val, thirdLineChance);
                    break;

                default:
                    chr.chatMessage(YELLOW, "Unhandled scroll " + scrollID);
                    break;
            }
        }
        c.write(CField.showItemUpgradeEffect(chr.getId(), success, false, scrollID, equip.getItemId()));
        c.write(WvsContext.inventoryOperation(true, false, ADD, ePos, (short) 0,
                0, equip));
        chr.consumeItem(scroll);
    }

    public static void handleUserAdditionalOptUpgradeItemUseRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        inPacket.decodeInt(); //tick
        short uPos = inPacket.decodeShort();
        short ePos = inPacket.decodeShort();
        byte bEnchantSkill = inPacket.decodeByte();
        Item scroll = chr.getInventoryByType(InvType.CONSUME).getItemBySlot(uPos);
        InvType invType = ePos < 0 ? EQUIPPED : EQUIP;
        Equip equip = (Equip) chr.getInventoryByType(invType).getItemBySlot(ePos);
        if (scroll == null || equip == null) {
            chr.chatMessage(GAME_MESSAGE, "Could not find scroll or equip.");
            return;
        }
        int scrollID = scroll.getItemId();
        boolean success;
        Map<ScrollStat, Integer> vals = ItemData.getItemInfoByID(scrollID).getScrollStats();
        int chance = vals.getOrDefault(ScrollStat.success, 100);
        int curse = vals.getOrDefault(ScrollStat.cursed, 0);
        success = Util.succeedProp(chance);
        if (success) {
            short val;
            int thirdLineChance = ItemConstants.THIRD_LINE_CHANCE;
            switch (scrollID) {
                case 2048305: // Bonus Pot
                case 2048308:
                case 2048309:
                case 2048310:
                case 2048311:
                case 2048313:
                case 2048314:
                case 2048316:
                case 2048329:
                    val = ItemGrade.HIDDEN_RARE.getVal();
                    equip.setHiddenOptionBonus(val, thirdLineChance);
                    break;
                case 2048306: // Special Bonus Pot
                case 2048307:
                case 2048315:
                case 2048331:
                    val = ItemGrade.HIDDEN_RARE.getVal();
                    equip.setHiddenOptionBonus(val, 100);
                    break;
                default:
                    chr.chatMessage(YELLOW, "Unhandled scroll " + scrollID);
                    break;
            }
        }
        chr.chatMessage(YELLOW, "Grade = " + equip.getGrade());
        for (int i = 0; i < 6; i++) {
            chr.chatMessage(YELLOW, "Opt " + i + " = " + equip.getOptions().get(i));
        }
        c.write(CField.showItemUpgradeEffect(chr.getId(), success, false, scrollID, equip.getItemId()));
        c.write(WvsContext.inventoryOperation(true, false, ADD, ePos, (short) 0,
                0, equip));
        chr.consumeItem(scroll);
    }

    public static void handleUserItemReleaseRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        inPacket.decodeInt(); //tick
        short uPos = inPacket.decodeShort();
        short ePos = inPacket.decodeShort();
        Item item = chr.getInventoryByType(InvType.CONSUME).getItemBySlot(uPos);
        InvType invType = ePos < 0 ? EQUIPPED : EQUIP;
        Equip equip = (Equip) chr.getInventoryByType(invType).getItemBySlot(ePos);
        if (equip == null) {
            chr.chatMessage(GAME_MESSAGE, "Could not find equip.");
            return;
        }
        boolean base = equip.getOptionBase(0) < 0;
        boolean bonus = equip.getOptionBonus(0) < 0;
        if (base && bonus) {
            equip.releaseOptions(true);
            equip.releaseOptions(false);
        } else {
            equip.releaseOptions(bonus);
        }
        chr.chatMessage(YELLOW, "Grade = " + equip.getGrade());
        for (int i = 0; i < 6; i++) {
            chr.chatMessage(YELLOW, "Opt " + i + " = " + equip.getOptions().get(i));
        }
        c.write(CField.showItemReleaseEffect(chr.getId(), ePos, bonus));
        c.write(WvsContext.inventoryOperation(true, false, ADD, ePos, (short) 0,
                0, equip));
    }

    public static void handleUserActiveNickItem(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        int nickItem = inPacket.decodeInt();
        chr.setNickItem(nickItem);
        chr.getField().broadcastPacket(UserRemote.setActiveNickItem(chr));
    }


    public static void handleLikePoint(Client c, InPacket inPacket) {
        //TODO
    }

    public static void handleUserActivateDanageSkin(Client c, InPacket inPacket) {
        int damageSkin = inPacket.decodeInt();
        Char chr = c.getChr();
        chr.setDamageSkin(damageSkin);
        c.write(User.setDamageSkin(chr));
    }


    public static void handleUserActivateDamageSkinPremium(Client c, InPacket inPacket) {
        int damageSkin = inPacket.decodeInt();
        Char chr = c.getChr();
        chr.setPremiumDamageSkin(damageSkin);
        c.write(User.setPremiumDamageSkin(chr));
    }

    public static void handleEventUiReq(Client c, InPacket inPacket) {
        //TODO: get opcodes for CUIContext::OnPacket
    }

    public static void handlePartyInvitableSet(Client c, InPacket inPacket) {
        c.getChr().setPartyInvitable(inPacket.decodeByte() != 0);
    }

    public static void handleZeroTag(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        int newTF = inPacket.decodeInt();
        int oldTF = inPacket.decodeInt();
        chr.swapZeroState();
    }

    public static void handleRequestSetBlessOfDarkness(Client c, InPacket inPacket) {
        Luminous.handleBlackBlessingIncrease(c);
    }

    public static void handleBattleRecordOnOffRequest(Client c, InPacket inPacket) {
        // CBattleRecordMan::RequestOnCalc
        boolean on = inPacket.decodeByte() != 0;
        boolean isNew = inPacket.decodeByte() != 0;
        boolean clear = inPacket.decodeByte() != 0;
        c.write(BattleRecordMan.serverOnCalcRequestResult(on));
    }

    public static void handleUserSelectNpc(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        int npcID = inPacket.decodeInt();
        short idk1 = inPacket.decodeShort();
        short idk2 = inPacket.decodeShort();
        Npc npc = (Npc) chr.getField().getLifeByObjectID(npcID);
        String script = npc.getScripts().get(0);
        if (script == null) {
            script = String.valueOf(npc.getTemplateId());
        }
        chr.getScriptManager().startScript(npc.getTemplateId(), script, ScriptType.NPC);
    }

    public static void handleUserScriptMessageAnswer(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        NpcMessageType nmt = chr.getScriptManager().getNpcScriptInfo().getMessageType();
        byte lastType = inPacket.decodeByte();
        byte action = inPacket.decodeByte();
        int answer = 0;
        if (nmt == NpcMessageType.AskMenu && action != -1) {
            answer = inPacket.decodeInt();
        }
        chr.getScriptManager().handleAction(ScriptType.NPC, lastType, action, answer);
    }

    public static void handleDropPickUpRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        byte fieldKey = inPacket.decodeByte();
        inPacket.decodeInt(); // tick
        Position pos = inPacket.decodePosition();
        int dropID = inPacket.decodeInt();
        inPacket.decodeInt(); // CliCrc
        // rest is some info about foreground info, not interested
        Field field = chr.getField();
        Life life = field.getLifeByObjectID(dropID);
        if (life != null && life instanceof Drop) {
            Drop drop = (Drop) life;
            chr.addDrop(drop);
            field.removeDrop(dropID, chr.getId(), false);
        }

    }

    public static void handleShowChair(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        int chrid = chr.getId();
        int itemid = inPacket.decodeInt();


        c.write(CField.showChair(chrid, itemid));
        WvsContext.dispose(chr);
    }

    public static void handleCancelChair(Client c, InPacket inpacket) {
        int chrid = c.getChr().getId();
        c.write(CField.cancelChair(chrid, -1));
    }

    public static void handleUserDropMoneyRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        inPacket.decodeInt(); // tick
        int amount = inPacket.decodeInt();
        if (chr.getMoney() > amount) {
            chr.deductMoney(amount);
            Drop drop = new Drop(-1, amount);
            chr.getField().drop(drop, chr.getPosition());
        }
    }

    public static void handleUserStatChangeItemUseRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        TemporaryStatManager tsm = chr.getTemporaryStatManager();
        inPacket.decodeInt(); // tick
        short slot = inPacket.decodeShort();
        int itemID = inPacket.decodeInt();
        Item item = chr.getConsumeInventory().getItemBySlot(slot);
        if (item == null || item.getItemId() != itemID) {
            return;
        }
        Map<SpecStat, Integer> specStats = ItemData.getItemInfoByID(itemID).getSpecStats();
        long time = specStats.getOrDefault(SpecStat.time, 0) / 1000;
        for (Map.Entry<SpecStat, Integer> entry : specStats.entrySet()) {
            SpecStat ss = entry.getKey();
            int value = entry.getValue();
            Option o = new Option(itemID, time);
            o.nOption = value;
            o.nReason = value;
            switch (ss) {
                case hp:
                    chr.heal(value);
                    break;
                case hpR:
                    chr.heal((int) ((value / 100D) * chr.getStat(Stat.mhp)));
                    break;
                case mp:
                    chr.healMP(value);
                    break;
                case mpR:
                    chr.healMP((int) ((value / 100D) * chr.getStat(Stat.mmp)));
                    break;
                case eva:
                    tsm.putCharacterStatValue(EVA, o);
                    break;
                case speed:
                    tsm.putCharacterStatValue(Speed, o);
                    break;
                case pad:
                    tsm.putCharacterStatValue(PAD, o);
                    break;
                case mad:
                    tsm.putCharacterStatValue(MAD, o);
                    break;
                case pdd:
                    tsm.putCharacterStatValue(PDD, o);
                    break;
                case mdd:
                    tsm.putCharacterStatValue(MDD, o);
                    break;
                case acc:
                    tsm.putCharacterStatValue(ACC, o);
                    break;
                case jump:
                    tsm.putCharacterStatValue(Jump, o);
                    break;
                case imhp:
                    tsm.putCharacterStatValue(MaxHP, o);
                    break;
                case immp:
                    tsm.putCharacterStatValue(MaxMP, o);
                    break;
                case indieAllStat:
                    tsm.putCharacterStatValue(IndieAllStat, o);
                    break;
                case indieSpeed:
                    tsm.putCharacterStatValue(IndieSpeed, o);
                    break;
                case indieSTR:
                    tsm.putCharacterStatValue(IndieSTR, o);
                    break;
                case indieDEX:
                    tsm.putCharacterStatValue(IndieDEX, o);
                    break;
                case indieINT:
                    tsm.putCharacterStatValue(IndieINT, o);
                    break;
                case indieLUK:
                    tsm.putCharacterStatValue(IndieLUK, o);
                    break;
                case indiePad:
                    tsm.putCharacterStatValue(IndiePAD, o);
                    break;
                case indiePdd:
                    tsm.putCharacterStatValue(IndiePDD, o);
                    break;
                case indieMad:
                    tsm.putCharacterStatValue(IndieMAD, o);
                    break;
                case indieMdd:
                    tsm.putCharacterStatValue(IndieMDD, o);
                    break;
                case indieBDR:
                    tsm.putCharacterStatValue(IndieBDR, o);
                    break;
                case indieIgnoreMobpdpR:
                    tsm.putCharacterStatValue(IndieIgnoreMobpdpR, o);
                    break;
                case indieStatR:
                    tsm.putCharacterStatValue(IndieStatR, o);
                    break;
                case indieMhp:
                    tsm.putCharacterStatValue(IndieMHP, o);
                    break;
                case indieMmp:
                    tsm.putCharacterStatValue(IndieMMP, o);
                    break;
                case indieBooster:
                    tsm.putCharacterStatValue(IndieBooster, o);
                    break;
                case indieAcc:
                    tsm.putCharacterStatValue(IndieACC, o);
                    break;
                case indieEva:
                    tsm.putCharacterStatValue(IndieEVA, o);
                    break;
                case indieAllSkill:
                    tsm.putCharacterStatValue(CombatOrders, o);
                    break;
                case indieMhpR:
                    tsm.putCharacterStatValue(IndieMHPR, o);
                    break;
                case indieMmpR:
                    tsm.putCharacterStatValue(IndieMMPR, o);
                    break;
                case indieStance:
                    tsm.putCharacterStatValue(IndieStance, o);
                    break;
                case indieForceSpeed:
                    tsm.putCharacterStatValue(IndieForceSpeed, o);
                    break;
                case indieForceJump:
                    tsm.putCharacterStatValue(IndieForceJump, o);
                    break;
                case indieQrPointTerm:
                    tsm.putCharacterStatValue(IndieQrPointTerm, o);
                    break;
                case indieWaterSmashBuff:
                    tsm.putCharacterStatValue(IndieUNK1, o);
                    break;
                case padRate:
                    tsm.putCharacterStatValue(IndiePADR, o);
                    break;
                case madRate:
                    tsm.putCharacterStatValue(IndieMADR, o);
                    break;
                case pddRate:
                    tsm.putCharacterStatValue(IndiePDDR, o);
                    break;
                case mddRate:
                    tsm.putCharacterStatValue(IndieMDDR, o);
                    break;
                case accRate:
                    tsm.putCharacterStatValue(ACCR, o);
                    break;
                case evaRate:
                    tsm.putCharacterStatValue(EVAR, o);
                    break;
                case mhpR:
                case mhpRRate:
                    tsm.putCharacterStatValue(IndieMHPR, o);
                    break;
                case mmpR:
                case mmpRRate:
                    tsm.putCharacterStatValue(IndieMHPR, o);
                    break;
                case booster:
                    tsm.putCharacterStatValue(Booster, o);
                    break;
                case expinc:
                    tsm.putCharacterStatValue(ExpBuffRate, o);
                    break;
                case str:
                    tsm.putCharacterStatValue(STR, o);
                    break;
                case dex:
                    tsm.putCharacterStatValue(DEX, o);
                    break;
                case inte:
                    tsm.putCharacterStatValue(INT, o);
                    break;
                case luk:
                    tsm.putCharacterStatValue(LUK, o);
                    break;
                case asrR:
                    tsm.putCharacterStatValue(AsrRByItem, o);
                    break;
                case bdR:
                    tsm.putCharacterStatValue(BdR, o);
                    break;
                case prob:
                    tsm.putCharacterStatValue(ItemUpByItem, o);
                    tsm.putCharacterStatValue(MesoUpByItem, o);
                    break;
            }
        }
        tsm.sendSetStatPacket();
        chr.consumeItem(item);
        chr.dispose();
    }

    public static void handleUserGatherItemRequest(Client c, InPacket inPacket) {
        inPacket.decodeInt(); // tick
        InvType invType = InvType.getInvTypeByVal(inPacket.decodeByte());
        Char chr = c.getChr();
        Inventory inv = chr.getInventoryByType(invType);
        List<Item> items = inv.getItems();
        items.sort(Comparator.comparingInt(Item::getBagIndex));
        for (Item item : items) {
            int firstSlot = inv.getFirstOpenSlot();
            if (firstSlot < item.getBagIndex()) {
                short oldPos = (short) item.getBagIndex();
                item.setBagIndex(firstSlot);
                chr.write(WvsContext.inventoryOperation(true, false, InventoryOperation.MOVE,
                        oldPos, (short) item.getBagIndex(), 0, item));
            }

        }
        c.write(WvsContext.gatherItemResult(invType.getVal()));
        chr.dispose();
    }

    public static void handleUserSortItemRequest(Client c, InPacket inPacket) {
        inPacket.decodeInt(); // tick
        InvType invType = InvType.getInvTypeByVal(inPacket.decodeByte());
        Char chr = c.getChr();
        Inventory inv = chr.getInventoryByType(invType);
        List<Item> items = inv.getItems();
        System.out.println();
        List<Integer> oldIndexes = new ArrayList<>();
        for (Item item : items) {
            oldIndexes.add(item.getBagIndex());
            chr.write(WvsContext.inventoryOperation(true, false, InventoryOperation.REMOVE,
                    (short) item.getBagIndex(), (short) 0, -1, item));
        }
        System.out.println();
        System.out.println();
        int i = 0;
        for (Item item : items) {
            item.setBagIndex(oldIndexes.get(i));
            chr.write(WvsContext.inventoryOperation(true, false, InventoryOperation.ADD,
                    (short) item.getBagIndex(), (short) 0, -1, item));
            i++;
        }
        c.write(WvsContext.sortItemResult(invType.getVal()));
        chr.dispose();
    }

    public static void handleUserScriptItemUseRequest(Client c, InPacket inPacket) {
        inPacket.decodeInt(); // tick
        short slot = inPacket.decodeShort();
        int itemID = inPacket.decodeInt();
        int quant = inPacket.decodeInt();
        Char chr = c.getChr();
        Item item = chr.getConsumeInventory().getItemBySlot(slot);
        if(item == null || item.getItemId() != itemID) {
            chr.dispose();
            return;
        }
        chr.getScriptManager().startScript(itemID, itemID + ".py", ScriptType.ITEM);
        chr.dispose();

    }

    public static void handleRWMultiChargeCancelRequest(Client c, InPacket inPacket) {
        byte unk = inPacket.decodeByte();
        int skillid = inPacket.decodeInt();

        c.write(UserLocal.onRWMultiChargeCancelRequest(unk, skillid));
    }

    public static void handleFoxManActiSetUseRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        inPacket.decodeInt(); // tick
        byte SkillNumber = inPacket.decodeByte(); //bSkill Number
        //more of the packet, but seems useless
        switch (SkillNumber) {
            case 3:
                Kanna.hakuFoxFire(chr);
                break;
            case 4:
                Kanna.hakuHakuBlessing(chr);
                break;
            case 5:
                Kanna.hakuBreathUnseen(chr);
                break;
        }
    }
}
