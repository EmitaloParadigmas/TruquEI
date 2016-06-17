package agent;
import java.util.ArrayList;
import java.io.ObjectOutputStream;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import domain.Card;
import domain.Deck;
import UI.PlayerUI;
import UI.PlayingPlayerUI;

public class Player extends Agent {

	private static final long serialVersionUID = -1729142182071611776L;

	public static final String PLAYER_TO_TABLE_TURN = "player-to-table";

	private PlayerUI searchForTableUI;
	private PlayingPlayerUI playingPlayerUI;
	
	private ArrayList<ACLMessage> seenReplies = new ArrayList<ACLMessage>();
	private AID[] tables;
	private AID bestTable = null;
	private ArrayList<ACLMessage> proposeReplies = new ArrayList<ACLMessage>();

	public String playerName;
	private Integer points = 0;

	private boolean isPlayerTurn = true;
	
	@Override
	protected void setup(){
		searchForTableUI = new PlayerUI(this);
		searchForTableUI.showGui();
	}

	private class JoinTableBehaviour extends OneShotBehaviour{

		private static final long serialVersionUID = 6983571473571663071L;

		@Override
		public void action() {
			AID[] tables = Player.this.getTables(myAgent);
						
			ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
			for (int i = 0; i < tables.length; ++i) {
				cfp.addReceiver(tables[i]);
			} 
			cfp.setContent("Procurando mesa");
			cfp.setConversationId("join-table");
			cfp.setReplyWith("Resposta da mesa");
			myAgent.send(cfp);
			MessageTemplate mt = MessageTemplate.MatchConversationId("join-table");
			
			Player.this.addBehaviour(new CheckTablesReplies(Player.this, 2000, mt));	
		}
	}
	
	private class CheckTablesReplies extends TickerBehaviour {
		
		private MessageTemplate mt;
		
		public CheckTablesReplies(Agent agent, long period, MessageTemplate mt){
			super(agent, period);
			this.mt = mt;
		}

		private static final long serialVersionUID = 5073894079459687671L;
			
		private boolean allTablesChecked(){
			return Player.this.seenReplies.size() >= Player.this.tables.length;
		}
		
		@Override
		protected void onTick() {
			ACLMessage reply = myAgent.receive(this.mt);
			if(reply != null){
				Player.this.seenReplies.add(reply);
				if (reply.getPerformative() == ACLMessage.PROPOSE) {
										
					String tablePlayersQuantity = reply.getContent();
					String turn = reply.getContent();
					AID tableToJoin = reply.getSender();	
					// Accept the propose to join the table

					searchForTableUI.update("Tentando entrar na mesa " + tableToJoin.getName());
					ACLMessage joinTableRequest = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
					joinTableRequest.addReceiver(tableToJoin);
					joinTableRequest.setContent(Player.this.playerName);
					joinTableRequest.setConversationId("join-table");
					myAgent.send(joinTableRequest);
					
					// Stop ticker to stop looking for tables
					this.done();
				}
				else if(reply.getPerformative() == ACLMessage.INFORM){
					// Player accepted to play, so dispose the screen to search for a game table
					searchForTableUI.dispose();
					
					// Create the screen to play
					playingPlayerUI = new PlayingPlayerUI(Player.this);
					playingPlayerUI.showGui();
					
					// Start the player behaviour to play the game
					Player.this.addBehaviour(new Play());
				}
				else if(reply.getPerformative() == ACLMessage.INFORM_REF){
					
					searchForTableUI.update(reply.getContent());

				}
				else{
					// In this case the propose was refused
					searchForTableUI.update("Nenhuma mesa disponível encontrada.");
				}
			}
			else{
				block();
			}
		}
		
	}
	
	public AID[] getTables(Agent myAgent){
		
		DFAgentDescription template = new DFAgentDescription();
		ServiceDescription sd = new ServiceDescription();
		sd.setType("blackjack-game");
		template.addServices(sd);
		DFAgentDescription[] result;
		try {
			result = DFService.search(myAgent, template);
			searchForTableUI.update("Pegando as mesas existentes:");
			
			tables = new AID[result.length];
			for (int i = 0; i < result.length; ++i) {
				tables[i] = result[i].getName();
				System.out.println(tables[i].getName());
			}

		} 
		catch (FIPAException e) {

		} 

		return tables;
	}
	
	public void joinTable(String playerName){
		this.playerName = playerName;
        this.addBehaviour( new JoinTableBehaviour());
	}
	
	// Keep a cyclic behaviour to check whether is the player turn
	private class Play extends CyclicBehaviour{

		@Override
		public void action() {

			// Try to match the table request informing that is this player turn
			MessageTemplate turnTemplate = MessageTemplate.and(
				MessageTemplate.MatchPerformative(ACLMessage.INFORM),
				MessageTemplate.MatchConversationId(GameTable.TABLE_TO_PLAYER_TURN)
			);
			
			ACLMessage message = myAgent.receive(turnTemplate);
			if(message != null){
				
				Player.this.playingPlayerUI.update(Player.this.playerName + ", sua vez!");
				System.out.println("Player "+ Player.this.playerName + " recebeu o INFORM que pode jogar.");
				
				Player.this.playingPlayerUI.showTableCard(message.getContent());
				
				Player.this.playFirstRound();
				
				// While still player turn, hold on
				while(Player.this.isPlayerTurn){
					System.out.println("Still player turn..");
				}
				
				if(!Player.this.isPlayerTurn){
					
					System.out.println("Player standed, informing table...");
					
					ACLMessage reply = message.createReply();
					AID table = message.getSender();
	
					reply.setPerformative(ACLMessage.INFORM);
	
					reply.setContent(Player.this.points.toString());
	
					reply.setConversationId(Player.PLAYER_TO_TABLE_TURN);
	
					myAgent.send(reply);
				}
			}
			else{
				System.out.println("Blocking Play()");
				this.block();
			}
		}
	}
	
	public void playFirstRound(){
//		this.getNewCard();
		this.getNewCard();
	}

	public void getNewCard() {
		Deck deck = Deck.getInstance();		
		Card card = deck.getTopCard();
		
		this.points += card.getRealValue();
		this.playingPlayerUI.showPlayerCard(card.toString());
		
		if(this.points >= 21){
			// Mandar para a mesa
		}
	}

	public void stand(){
		System.out.println("Player " + this.playerName + " passou a vez.");
		this.isPlayerTurn  = false;
	}
}