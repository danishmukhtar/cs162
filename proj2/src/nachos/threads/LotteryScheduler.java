package nachos.threads;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

import nachos.machine.Lib;
import nachos.machine.Machine;
import nachos.threads.PriorityScheduler.PriorityQueue;
import nachos.threads.PriorityScheduler.ThreadState;
import nachos.threads.PriorityScheduler.ThreadWaiter;

/**
 * A scheduler that chooses threads using a lottery.
 * 
 * <p>
 * A lottery scheduler associates a number of tickets with each thread. When a
 * thread needs to be dequeued, a random lottery is held, among all the tickets
 * of all the threads waiting to be dequeued. The thread that holds the winning
 * ticket is chosen.
 * 
 * <p>
 * Note that a lottery scheduler must be able to handle a lot of tickets
 * (sometimes billions), so it is not acceptable to maintain state for every
 * ticket.
 * 
 * <p>
 * A lottery scheduler must partially solve the priority inversion problem; in
 * particular, tickets must be transferred through locks, and through joins.
 * Unlike a priority scheduler, these tickets add (as opposed to just taking the
 * maximum).
 */
public class LotteryScheduler extends PriorityScheduler {
	/**
	 * Allocate a new lottery scheduler.
	 */
	public LotteryScheduler() {
	}

	/**
	 * Allocate a new lottery thread queue.
	 * 
	 * @param transferPriority
	 *            <tt>true</tt> if this queue should transfer tickets from
	 *            waiting threads to the owning thread.
	 * @return a new lottery thread queue.
	 */
	public ThreadQueue newThreadQueue(boolean transferPriority) {
		return new LotteryQueue(transferPriority);
	}

	protected ThreadState getThreadState(KThread thread) {
		if (thread.schedulingState == null)
			thread.schedulingState = new ThreadState(thread);

		return (ThreadState) thread.schedulingState;
	}

	public void setPriority(KThread thread, int priority) {
		Lib.assertTrue(Machine.interrupt().disabled());

		Lib.assertTrue(priority >= priorityMinimum
				&& priority <= priorityMaximum);

		getThreadState(thread).setPriority(priority);
	}

	public boolean increasePriority() {
		boolean intStatus = Machine.interrupt().disable();

		KThread thread = KThread.currentThread();

		int priority = getPriority(thread);
		if (priority == priorityMaximum)
			return false;

		setPriority(thread, priority + 1);

		Machine.interrupt().restore(intStatus);
		return true;
	}

	public boolean decreasePriority() {
		boolean intStatus = Machine.interrupt().disable();

		KThread thread = KThread.currentThread();

		int priority = getPriority(thread);
		if (priority == priorityMinimum)
			return false;

		setPriority(thread, priority - 1);

		Machine.interrupt().restore(intStatus);
		return true;
	}

	public static void testSimpleDonation() {
		final int[] a = { 1 };
		final Lock lock = new Lock();
		final KThread donor = new KThread(new Runnable() {
			public void run() {
				lock.acquire();
				while (a[0] > 0) {
					KThread.yield();
				}
				
				Machine.interrupt().disable();
				System.out.println("EP of the Donor = " + ThreadedKernel.scheduler.getEffectivePriority());
				Machine.interrupt().enable();
				System.out.println("Donor finishes.");
				lock.release();
			}
		});
		final KThread middle = new KThread(new Runnable() {
			public void run() {
				lock.acquire();
				
				while (a[0] > 0) {
					KThread.yield();
				}
				
				Machine.interrupt().disable();
				System.out.println("EP of the Middle = " + ThreadedKernel.scheduler.getEffectivePriority());
				Machine.interrupt().enable();
				System.out.println("Middle finishes.");
				lock.release();
			}
		});
		final KThread receiver = new KThread(new Runnable() {
			public void run() {
				lock.acquire();
				
				KThread.yield();
				a[0] = 0;
				Machine.interrupt().disable();
				System.out.println("EP of the Receiver = " + ThreadedKernel.scheduler.getEffectivePriority());
				Machine.interrupt().enable();
				System.out.println("Receiver finishes.");
				lock.release();
			}
		});

		System.out.println("-------- LotteryScheduler Test: Ticket donation and priority inversion --------");
		System.out.println("Expected Receiver to finish first.");

		Machine.interrupt().disable();
		ThreadedKernel.scheduler.setPriority(receiver, 1000);
		ThreadedKernel.scheduler.setPriority(donor, 1000);
		ThreadedKernel.scheduler.setPriority(middle, 600);
		Machine.interrupt().enable();
		receiver.fork();
		donor.fork();
		middle.fork();
		Machine.interrupt().disable();
		System.out.println("EP of the main thread = " + ThreadedKernel.scheduler.getEffectivePriority());
		Machine.interrupt().enable();
		ThreadedKernel.alarm.waitUntil(10000000);
		System.out.println("-------- End testing --------");
	}
	
	
	public static void testSimple() {
		final int[] a = { 1 };
		final Lock lock = new Lock();
		final Condition2 con_var = new Condition2(lock);
		final KThread donor = new KThread(new Runnable() {
			public void run() {
				lock.acquire();
				while (a[0] > 0) {
					con_var.sleep();
				}
				con_var.wake();
				System.out.println("Donor finishes.");
				lock.release();
			}
		});
		final KThread middle = new KThread(new Runnable() {
			public void run() {
				lock.acquire();
				while (a[0] > 0) {
					con_var.sleep();
				}
				con_var.wake();
				System.out.println("Middle finishes.");
				lock.release();
			}
		});
		final KThread receiver = new KThread(new Runnable() {
			public void run() {
				lock.acquire();
				a[0] = 0;
				Machine.interrupt().disable();
				System.out.println("EP of the receiver = " + ThreadedKernel.scheduler.getEffectivePriority());
				Machine.interrupt().enable();
				con_var.wakeAll();
				System.out.println("Receiver finishes.");
				lock.release();
			}
		});

		System.out.println("-------- LotteryScheduler Test: Ticket donation and priority inversion --------");
		System.out.println("Expected Receiver to finish first.");

		Machine.interrupt().disable();
		ThreadedKernel.scheduler.setPriority(receiver, 10);
		ThreadedKernel.scheduler.setPriority(donor, 1000);
		ThreadedKernel.scheduler.setPriority(middle, 600);
		Machine.interrupt().enable();
		receiver.fork();
		donor.fork();
		middle.fork();
		Machine.interrupt().disable();
		System.out.println("EP of the main thread = " + ThreadedKernel.scheduler.getEffectivePriority());
		Machine.interrupt().enable();
		ThreadedKernel.alarm.waitUntil(100000);
		System.out.println("-------- End testing --------");
	}
	
	public static void testAddOrSubtract() {
		final int[] a = { 1 };
		final int[] b = { 20 };
		final Lock lock = new Lock();
		final Condition2 con_var = new Condition2(lock);
		final KThread decreaser = new KThread(new Runnable() {
			public void run() {
				lock.acquire();
				while (b[0] > 1) {
					Machine.interrupt().disable();
					ThreadedKernel.scheduler.decreasePriority();
					System.out.println("Number of tickets of Decreaser = "
							+ ThreadedKernel.scheduler.getPriority());
					Machine.interrupt().enable();
					b[0] -= 1;
					con_var.wake();
					con_var.sleep();
				}
				con_var.wake();
				System.out.println("Decreaser finishes.");
				lock.release();
			}
		});

		final KThread increaser = new KThread(new Runnable() {
			public void run() {
				lock.acquire();
				while (a[0] < 20) {
					Machine.interrupt().disable();
					ThreadedKernel.scheduler.increasePriority();
					System.out.println("Number of tickets of Increaser = "
							+ ThreadedKernel.scheduler.getPriority());
					Machine.interrupt().enable();
					a[0] += 1;
					con_var.wake();
					con_var.sleep();
				}
				con_var.wake();
				System.out.println("Increaser finishes.");
				lock.release();
			}
		});

		System.out
				.println("-------- LotteryScheduler Test: increasePriority and decreasePriority --------");

		Machine.interrupt().disable();
		ThreadedKernel.scheduler.setPriority(increaser, 1);
		ThreadedKernel.scheduler.setPriority(decreaser, 200);
		System.out.println("Starting number of tickets of increaser = 1");
		System.out.println("Starting number of tickets of decreaser = 20");
		Machine.interrupt().enable();

		increaser.fork();
		decreaser.fork();

		ThreadedKernel.alarm.waitUntil(10000);
		System.out
				.println("------------------------------- End Test -------------------------------------");
	}

	/**
	 * The default priority for a new thread. Do not change this value.
	 */
	public static final int priorityDefault = 1;
	/**
	 * The minimum priority that a thread can have. Do not change this value.
	 */
	public static final int priorityMinimum = 1;
	/**
	 * The maximum priority that a thread can have. Do not change this value.
	 */
	public static final int priorityMaximum = Integer.MAX_VALUE;

	protected class LotteryQueue extends ThreadQueue {
		private java.util.HashMap<ThreadState, Integer> waitQueue;
		boolean transferPriority;
		private ThreadState acquired;
		private int sum;
		boolean sumChange;

		LotteryQueue(boolean transferPriority) {
			waitQueue = new java.util.HashMap<ThreadState, Integer>();
			this.transferPriority = transferPriority;
			sum = 0;
			sumChange = true;
		}

		public void signal(ThreadState ts, int tickets) {
			
			if (transferPriority == true && acquired != null) {
				if (acquired == ts) {
					acquired.setEffectivePriority(tickets - sum);
				} else {
					acquired.effectivePriorityUpdated();
				}
			}
		}

		public void updateWaitingThread(ThreadState ts, int tickets) {
			if (waitQueue.size() == 0) {
				return;
			}

			// update the value of a threadstate on the queue
			waitQueue.put(ts, new Integer(Math.min(tickets,  threadTickets(ts))));

			// flag the queue that its sum has changed and announce to others
			sumChange = true;
			signal(ts, tickets);
		}

		public void dequeueWaitingThread(ThreadState ts) {
			Lib.assertTrue(waitQueue.containsKey(ts));

			// remove the thread from the lottery queue
			Integer tickets = waitQueue.remove(ts);

			// flag the queue that its sum has changed
			sumChange = true;

			signal(ts, tickets.intValue());
		}

		// DONE
		public void enqueueWaitingThread(ThreadState ts) {
			int tickets = threadTickets(ts);

			Lib.assertTrue(!waitQueue.containsKey(ts));

			waitQueue.put(ts, new Integer(tickets));

			// flag the queue that its sum has changed
			sumChange = true;
			signal(ts, tickets);

		}

		public void waitForAccess(KThread thread) {
			Lib.assertTrue(Machine.interrupt().disabled());
			ThreadState ts = getThreadState(thread);

			enqueueWaitingThread(ts);
			ts.waitForAccess(this);
		}

		public void acquire(KThread thread) {
			Lib.assertTrue(Machine.interrupt().disabled());

			ThreadState ts = getThreadState(thread);

			// unacquire previous
			if (acquired != null) {
				acquired.unacquire(this);
			}

			// if thread on waiting queue, dequeue it
			if (waitQueue.containsKey(ts)) {
				dequeueWaitingThread(ts);
			}

			this.acquired = ts;
			ts.acquire(this);

		}

		public KThread nextThread() {
			Lib.assertTrue(Machine.interrupt().disabled());

			ThreadState ts = pickNextThread();
			if (ts == null) {
				if (acquired != null) {
					acquired.unacquire(this);
				}
				return null;
			}
			acquire(ts.thread);
			return ts.thread;
		}

		// Hold a lottery when picks a new thread - DONE
		protected ThreadState pickNextThread() {
			int total = 0;
			int num;
			ThreadState result = null;
			Random rand = new Random();
			if (waitQueue.size() == 0) {
				return null;
			}
			num = rand.nextInt(getSum()) + 1;
			Iterator<ThreadState> iter = waitQueue.keySet().iterator();
			while (num > total && iter.hasNext()) {
				result = iter.next();
				total += waitQueue.get(result).intValue();
			}
			return result;
		}

		public int getSum() {
			if (sumChange) {
				return updateSum();
			} else {
				return sum;
			}

		}

		private int updateSum() {
			Lib.assertTrue(sumChange == true);
			int total = 0;
			Map.Entry<ThreadState, Integer> value;
			Iterator<Map.Entry<ThreadState, Integer>> iter = waitQueue
					.entrySet().iterator();
			while (iter.hasNext()) {
				value = iter.next();
				total += value.getValue().intValue();
			}
			sum = total;
			sumChange = false;
			return sum;
		}
		
		private int threadTickets(ThreadState ts){
			if (transferPriority){
				return ts.getEffectivePriority();
			}
			else {
				return ts.getPriority();
			}
		}

		public boolean contains(KThread thread) {
			ThreadState ts = getThreadState(thread);
			return waitQueue.containsKey(ts);
		}

		public void print() {
			Lib.assertTrue(Machine.interrupt().disabled());
			System.out.println("Number of tickets = " + getSum()
					+ "\tNumber of threads on queue = " + size());
			System.out.println(waitQueue.toString());
		}

		public boolean empty() {
			return waitQueue.size() == 0;
		}

		public boolean isValid() {
			return !sumChange;
		}

		public int size() {
			return waitQueue.size();
		}
	}

	public class ThreadState extends PriorityScheduler.ThreadState {
		public ThreadState(KThread thread) {
			super(thread);
		}

		public void setPriority(int priority) {
			this.priority = priority;
			effectivePriorityUpdated();
		}

		public int getEffectivePriority() {
			return effectivePriority;
		}

		public void setEffectivePriority(int value) {
			if (value < this.priority) {
				this.effectivePriority = this.priority;
			} else {
				this.effectivePriority = value;
			}
			announcePrioChange();
		}

		public int calculateEffectivePriority() {
			int sum = priority;
			int queueSum = 0;
			Iterator<ThreadQueue> iter = acquiredpqs.iterator();
			LotteryQueue queue;
			while (iter.hasNext()) {
				queue = (LotteryQueue) iter.next();
				queueSum = queue.getSum();
				if (Integer.MAX_VALUE - sum < queueSum) {
					return Integer.MAX_VALUE;
				}
				sum += queueSum;
			}
			return sum;
		}

		void effectivePriorityUpdated() {
			int newEffectivePriority = calculateEffectivePriority();
			if (newEffectivePriority != effectivePriority) {
				effectivePriority = newEffectivePriority;
				announcePrioChange();
			}
		}

		// Mark the waitQueue the queue that this thread is waiting on
		public void waitForAccess(ThreadQueue waitQueue) {
			waitingpqs.add(waitQueue);
		}

		// Thread acquires a queue
		public void acquire(ThreadQueue waitQueue) {
			waitingpqs.remove(waitQueue);
			acquiredpqs.add(waitQueue);
			effectivePriorityUpdated();
		}

		// Unacquire the thread from the queue
		public void unacquire(ThreadQueue noQueue) {
			acquiredpqs.remove(noQueue);
			effectivePriorityUpdated();
		}

		// Announce that the thread's effective priority has been changed by
		// asking the queue to update
		void announcePrioChange() {
			// starter = true;
			LotteryQueue queue;
			Iterator<ThreadQueue> iter;
			iter = waitingpqs.iterator();
			while (iter.hasNext()) {
				queue = (LotteryQueue) iter.next();
				queue.updateWaitingThread(this, effectivePriority);
			}
		}
	}
}
