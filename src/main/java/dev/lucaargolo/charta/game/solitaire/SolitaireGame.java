package dev.lucaargolo.charta.game.solitaire;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import dev.lucaargolo.charta.blockentity.CardTableBlockEntity;
import dev.lucaargolo.charta.game.*;
import dev.lucaargolo.charta.menu.AbstractCardMenu;
import dev.lucaargolo.charta.sound.ModSounds;
import dev.lucaargolo.charta.utils.CardImage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerLevelAccess;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class SolitaireGame extends CardGame<SolitaireGame> {

    private final GameSlot stockPile;
    private final GameSlot wastePile;

    private final Map<Suit, GameSlot> foundationPiles;
    private final List<GameSlot> tableauPiles;

    public SolitaireGame(List<CardPlayer> players, CardDeck deck) {
        super(players, deck);

        float middleX = CardTableBlockEntity.TABLE_WIDTH/2f;
        float leftX = middleX - CardImage.WIDTH/2f - (CardImage.WIDTH + 5)*3;

        float middleY = CardTableBlockEntity.TABLE_HEIGHT/2f;
        float topY = middleY + (1.75f * CardImage.HEIGHT);

        this.stockPile = addSlot(new GameSlot(new LinkedList<>(), leftX, topY, 0f, 0f));
        this.wastePile = addSlot(new GameSlot(new LinkedList<>(), leftX + CardImage.WIDTH + 5, topY, 0f, 0f));

        int i = 0;
        ImmutableMap.Builder<Suit, GameSlot> map = ImmutableMap.builder();
        for(Suit suit : List.of(Suit.SPADES, Suit.HEARTS, Suit.CLUBS, Suit.DIAMONDS)) {
            map.put(suit, addSlot(new GameSlot(new LinkedList<>(), leftX + (CardImage.WIDTH + 5)*(3+i++), topY, 0f, 0f)));
        }
        this.foundationPiles = map.build();

        ImmutableList.Builder<GameSlot> list = ImmutableList.builder();
        for(i = 0; i < 7; i++) {
            list.add(addSlot(new GameSlot(new LinkedList<>(), leftX + CardImage.WIDTH + (CardImage.WIDTH + 5)*i, topY + 5f + CardImage.HEIGHT - (CardImage.HEIGHT*1.5f), 0f, 180f, Direction.NORTH, CardImage.HEIGHT*3, false)));
        }
        this.tableauPiles = list.build();
    }

    @Override
    public AbstractCardMenu<SolitaireGame> createMenu(int containerId, Inventory playerInventory, ServerLevel level, BlockPos pos, CardDeck deck) {
        return new SolitaireMenu(containerId, playerInventory, ContainerLevelAccess.create(level, pos), deck, players.stream().mapToInt(CardPlayer::getId).toArray(), this.getRawOptions());
    }

    @Override
    public Predicate<CardDeck> getDeckPredicate() {
        return deck -> {
            for(Suit suit : Suit.values()) {
                for(Rank rank : Rank.values()) {
                    if(suit != Suit.BLANK && rank != Rank.BLANK && rank != Rank.JOKER && !deck.getCards().contains(new Card(suit, rank))) {
                        return false;
                    }
                }
            }
            return true;
        };
    }

    @Override
    public Predicate<Card> getCardPredicate() {
        return card -> card.getSuit() != Suit.BLANK && card.getRank() != Rank.BLANK && card.getRank() != Rank.JOKER;
    }

    @Override
    public boolean canPlay(CardPlayer player, CardPlay play) {
        return false;
    }

    @Override
    public void startGame() {
        this.stockPile.clear();
        this.wastePile.clear();

        this.foundationPiles.values().forEach(GameSlot::clear);
        this.tableauPiles.forEach(GameSlot::clear);

        this.stockPile.addAll(gameDeck);
        this.stockPile.shuffle();

        for (CardPlayer player : players) {
            player.resetPlay();
            player.getHand().clear();
            getCensoredHand(player).clear();
        }

        for (int i = 0; i < this.tableauPiles.size(); i++) {
            for (int j = 0; j < this.tableauPiles.size(); j++) {
                int slot = j;
                int amount = i;
                if(slot >= amount) {
                    this.scheduledActions.add(() -> {
                        this.currentPlayer.playSound(ModSounds.CARD_DRAW.get());
                        Card card = this.stockPile.removeLast();
                        if(slot == amount) card.flip();
                        this.tableauPiles.get(slot).addLast(card);
                    });
                    this.scheduledActions.add(() -> {});
                }
            }
        }

        this.currentPlayer = players.getFirst();
        this.isGameReady = false;
        this.isGameOver = false;

        table(Component.translatable("message.charta.game_started"));
    }

    @Override
    public void runGame() {
        if(!isGameReady) {
            return;
        }
        currentPlayer.afterPlay(play -> {
            //Setup next play.
            currentPlayer.resetPlay();

            if(play != null) {
                //If they successfully did a play, unflip the last card from the tableau
                GameSlot s = this.getSlot(play.slot());
                if(!s.isEmpty() && s.getLast().isFlipped()) {
                    s.getLast().flip();
                    s.setDirty(true);
                    play(currentPlayer, Component.translatable("message.charta.revealed_a_card", Component.translatable(deck.getCardTranslatableKey(s.getLast())), play.slot()-5));
                }else{
                    play(currentPlayer, Component.translatable("message.charta.did_a_move"));
                }
            }else{
                play(currentPlayer, Component.translatable("message.charta.did_a_move"));
            }

            //Check if the stockpile is empty
            if(this.stockPile.isEmpty()) {
                this.wastePile.forEach(Card::flip);
                this.wastePile.reverse();
                this.stockPile.addAll(this.wastePile);
                this.wastePile.clear();
            }

            boolean allEmpty = true;
            for(GameSlot slot : this.tableauPiles) {
                allEmpty = allEmpty && slot.isEmpty();
            }

            if(allEmpty) {
                endGame();
            }else{
                runGame();
            }
        });
    }

    @Override
    public void endGame() {
        boolean allEmpty = true;
        for(GameSlot slot : this.tableauPiles) {
            allEmpty = allEmpty && slot.isEmpty();
        }
        if(allEmpty) {
            currentPlayer.sendTitle(Component.translatable("message.charta.you_won").withStyle(ChatFormatting.GREEN), Component.translatable("message.charta.congratulations"));
        }else{
            currentPlayer.sendTitle(Component.translatable("message.charta.you_lost").withStyle(ChatFormatting.RED), Component.translatable("message.charta.give_up"));
        }

        this.isGameOver = true;

    }

    @Override
    public int getMinPlayers() {
        return 1;
    }

    @Override
    public int getMaxPlayers() {
        return 1;
    }

    @Override
    public List<GameOption<?>> getOptions() {
        return List.of();
    }

    public static boolean isAlternate(Card c1, Card c2) {
        boolean v1 = (c1.getSuit() == Suit.HEARTS || c1.getSuit() == Suit.DIAMONDS);
        boolean v2 = (c2.getSuit() == Suit.HEARTS || c2.getSuit() == Suit.DIAMONDS);
        return v1 != v2;
    }
}
