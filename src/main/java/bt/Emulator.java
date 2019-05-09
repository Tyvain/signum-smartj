package bt;

import java.util.ArrayList;

import burst.kit.burst.BurstCrypto;
import burst.kit.entity.BurstID;

/**
 * Emulates the blockchain for debugging/testing purposes.
 * 
 * @author jjos
 *
 */
public class Emulator {

	static final Emulator instance = new Emulator();

	Block genesis;
	Transaction curTx;

	/**
	 * Block being forged, also representing the mempool.
	 */
	Block currentBlock;
	Block prevBlock;

	ArrayList<Block> blocks = new ArrayList<Block>();
	ArrayList<Transaction> txs = new ArrayList<Transaction>();
	ArrayList<Address> addresses = new ArrayList<Address>();

	public ArrayList<Block> getBlocks() {
		return blocks;
	}

	public ArrayList<Transaction> getTxs() {
		return txs;
	}

	public ArrayList<Address> getAddresses() {
		return addresses;
	}

	Emulator() {
		currentBlock = genesis = new Block(null);
		try {
			forgeBlock();
		} catch (Exception e) {
			// Should never happen
			e.printStackTrace();
		}
	}

	public static void main(String[] args) throws Exception {
		Emulator e = Emulator.getInstance();

		Address add1 = e.getAddress("BURST-C6HC-TZF2-FXPU-GCCSC");
		Address add2 = e.getAddress("BURST-9VBL-B3KR-HE6P-98L5G");

		e.airDrop(add1, 100000);
		e.airDrop(add2, 100000);

		Address c1 = e.getAddress("AUCTION-1");
		e.createConctract(add1, c1, "test.Auction", Contract.ONE_BURST);
		e.forgeBlock();

		e.send(add1, c1, 1000 * Contract.ONE_BURST);
		e.forgeBlock();
	}

	public Address findAddress(String rs) {
		for (Address a : addresses) {
			if (a.rsAddress.equals(rs))
				return a;
		}
		return null;
	}

	public Address getAddress(String rs) {
		Address ret = findAddress(rs);
		if (ret != null)
			return ret;

		BurstCrypto bc = BurstCrypto.getInstance();
		long id = 0L;
		try {
			// Decode without the BURST- prefix
			BurstID ad = bc.rsDecode(rs.substring(6));
			id = ad.getSignedLongId();
		} catch (Exception e) {
			// not a valid address, do nothing on the emulator
		}
		ret = new Address(id, 0, rs);
		addresses.add(ret);

		return ret;
	}

	public static Emulator getInstance() {
		return instance;
	}

	public void send(Address from, Address to, long amount) {
		send(from, to, amount, (String) null);
	}

	public void send(Address from, Address to, long amount, String message) {
		Transaction t = new Transaction(from, to, amount, Transaction.TYPE_PAYMENT,
				new Timestamp(currentBlock.height, currentBlock.txs.size()), message);
		currentBlock.txs.add(t);
		t.block = currentBlock;
		txs.add(t);
	}

	public void send(Address from, Address to, long amount, Register message) {
		Transaction t = new Transaction(from, to, amount,
				message.method != null ? Transaction.TYPE_METHOD_CALL : Transaction.TYPE_PAYMENT,
				new Timestamp(currentBlock.height, currentBlock.txs.size()), message);
		currentBlock.txs.add(t);
		t.block = currentBlock;
		txs.add(t);
	}

	public void createConctract(Address from, Address to, String contractClass, long actFee) {
		Transaction t = new Transaction(from, to, actFee, Transaction.TYPE_AT_CREATE,
				new Timestamp(currentBlock.height, currentBlock.txs.size()), contractClass);
		currentBlock.txs.add(t);
		t.block = currentBlock;
		txs.add(t);
	}

	public void airDrop(Address to, long amount) {
		to.balance += amount;
	}

	public void forgeBlock() throws Exception {

		// Transactions to postpone due to sleeping contracts
		ArrayList<Transaction> pendTxs = new ArrayList<>();
		Timestamp curBlockTs = new Timestamp(currentBlock.height, 0);

		// check for sleeping contracts
		for(Block b : blocks){
			for(Transaction tx : b.txs){
				Contract c = tx.receiver.contract;
				// sleeping contract
				if(c.sleepUntil!=null && c.sleepUntil.le(curBlockTs)) {
					// release to finish execution
					c.semaphore.release();
					// FIXME: consecutive sleep commands in contracts will not work, fix or not fix?
					while(c.running && c.sleepUntil!=null){
						Thread.sleep(10);
					}
				}
			}
		}

		// process all pending transactions
		for (Transaction tx : currentBlock.txs) {

			// checking for sleeping contracts
			if (tx.receiver.contract != null && tx.receiver.contract.sleepUntil != null) {
				// let it sleep, postpone this transaction
				pendTxs.add(tx);
				continue;
			}

			if (tx.amount > 0) {
				long ammount = Math.min(tx.sender.balance, tx.amount);

				tx.sender.balance -= ammount;
				tx.receiver.balance += ammount;
			}

			if (tx.type == Transaction.TYPE_AT_CREATE) {
				// set the current creator variables
				curTx = tx;
				Object ocontract = Class.forName(tx.msgString).getConstructor().newInstance();
				if (ocontract instanceof Contract) {
					Contract c = (Contract) ocontract;

					c.address = tx.receiver;
					tx.receiver.contract = c;
				}
			}
		}

		blocks.add(currentBlock);
		prevBlock = currentBlock;
		currentBlock = new Block(prevBlock);
		currentBlock.txs.addAll(pendTxs);

		// run all contracts, operations will be pending to be forged in the next block
		for (Transaction tx : prevBlock.txs) {

			if (tx.receiver == null)
				continue;

			// Check for contract
			Contract c = tx.receiver.contract;
			if (c != null && tx.type != Transaction.TYPE_AT_CREATE && tx.amount >= c.activationFee) {
				// a contract received a message
				c.setCurrentTx(tx);

				Thread ct = new Thread() {
					public void run() {
						// check the message arguments to call a specific function
						boolean invoked = false;
						try {
							if (tx.type == Transaction.TYPE_METHOD_CALL) {
								invoked = true;
								if (tx.msg.args[0] == null)
									tx.msg.method.invoke(c);
								else if (tx.msg.args[1] == null)
									tx.msg.method.invoke(c, tx.msg.args[0]);
								else if (tx.msg.args[2] == null)
									tx.msg.method.invoke(c, tx.msg.args[0], tx.msg.args[1]);
								else
									tx.msg.method.invoke(c, tx.msg.args[0], tx.msg.args[1], tx.msg.args[2]);
							}
						} catch (Exception ex) {
							ex.printStackTrace();
							invoked = false;
						}
						if (!invoked) // invoke the default method "txReceived"
							c.txReceived();
						c.running = false;
					}
				};

				// Run the contract on a different thread so that we can emulate the sleep function.
				// However, we always wait for it to finish one by one since there should be no
				// parallel execution.
				c.semaphore.acquire();
				c.running = true;
				ct.start();
				while(c.running && c.sleepUntil!=null){
					Thread.sleep(10);
				}
				if(c.sleepUntil==null)
					c.semaphore.release();
			}
		}
	}

	public Transaction getTxAfter(Address receiver, Timestamp ts) {
		Block b = blocks.get(0);
		while (b != null) {
			for (int i = 0; i < b.txs.size(); i++) {
				Transaction txi = b.txs.get(i);
				if (txi.type != Transaction.TYPE_AT_CREATE && txi.receiver.equals(receiver)
						&& !txi.getTimestamp().le(ts))
					return txi;
			}
			b = b.next;
		}
		return null;
	}

	public Block getPrevBlock() {
		return prevBlock;
	}

	public Block getCurrentBlock() {
		return currentBlock;
	}

}
