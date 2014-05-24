/*
 * ------------------------------------------------------------------------
 *  Copyright by KNIME GmbH, Konstanz, Germany
 *  Website: http://www.knime.org; Email: contact@knime.org
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME GMBH herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ------------------------------------------------------------------------
 *
 */
package org.knime.core.util;

import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;

import org.knime.core.node.KNIMEConstants;
import org.knime.core.node.NodeLogger;

/** An abstract class to process elements of an Iterable simultaneously.
 * The output of the computation can be any class, whereby it must solely be
 * based on a single input record (due to the parallel processing nature).
 *
 * <p>The worker threads being used can be either from the global
 * {@link KNIMEConstants#GLOBAL_THREAD_POOL KNIME threadpool} or any
 * {@link Executor}, which is set using the {@link #setExecutor(Executor)}
 * method.
 *
 * <p>The generated output needs to be processed in the (abstract)
 * {@link #processFinished(ComputationTask)} method, whereby this method is
 * guaranteed to be not called concurrently in different threads
 * (called sequentially for each finished computation, possibly by different
 * worker threads). The order the output arrives is equivalent to the input
 * order. This class uses an internal cache to ensure this ordering; the size
 * of the cache is determined by a constructor argument.
 *
 * @param <In> The type of input to be processed. The <code>Iterable</code>
 * passed in the {@link #run(Iterable)} method contains elements of this type.
 * Each element is processed in its own (reusable) thread.
 * @param <Out> The output type generated by the {@link #compute(Object, long)}
 * method. An output is derived from a corresponding input.
 * @author Bernd Wiswedel, KNIME.com, Zurich, Switzerland
 */
public abstract class MultiThreadWorker<In, Out> {

    private final NodeLogger m_logger = NodeLogger.getLogger(getClass());

    /** Limits the number of simultaneously running computations. */
    private final Semaphore m_maxActiveInstanceSemaphore;

    /** Limits the number of finished but not finally processed
     * computations (used to ensure output ordering). */
    private final Semaphore m_maxQueueSemaphore;

    /** Map of finished computations, maps input index (iterator index) to
     * computation. Used to ensure output ordering. */
    private final HashMap<Long, ComputationTask> m_finishedTasks;

    /** Map of currently running tasks (used for cancelation). */
    private final ConcurrentHashMap<Long, ComputationTask> m_activeTasks;

    /** Next output index. */
    private long m_nextFinishedIndex;

    /** Next input index. */
    private long m_nextSubmittedIndex;

    /** Executor to be used, if null use the KNIME global thread pool. */
    private Executor m_executor;

    /** Exception pointer, if non-null the execution will abort. */
    private Exception m_exception;

    /** Thread running the {@link #run(Iterable)} method (kept to be able to
     * interrupt it). */
    private Thread m_mainThread;

    /** Maximum number of finished computations that are not
     * finally processed. */
    private final int m_maxQueueSize;

    /** Maximum number of simultaneous computations. */
    private final int m_maxActiveInstanceSize;

    /** Whether {@link #cancel(boolean)} has been called. */
    private volatile boolean m_isCanceled;

    /** Creates new worker with a bounded finished job queue and a maximum
     * number of active jobs.
     * @param maxQueueSize Maximum queue size of finished jobs (finished
     * computations might be cached in order to ensure the proper output
     * ordering). If this queue is full (because the next-to-be-processed
     * computation is still ongoing), no further tasks are submitted.
     * @param maxActiveInstanceSize The maximum number of simultaneously running
     * computations (unless otherwise bound by the used executor).
     * @throws IllegalArgumentException if queue size
     * &lt; running instance count
     */
    public MultiThreadWorker(final int maxQueueSize,
            final int maxActiveInstanceSize) {
        if (maxQueueSize < maxActiveInstanceSize) {
            throw new IllegalArgumentException("Queue size must be as least as"
                    + " large as running instance count: " + maxQueueSize
                    + " vs. " + maxActiveInstanceSize);
        }
        m_maxQueueSemaphore = new Semaphore(maxQueueSize);
        m_maxActiveInstanceSemaphore = new Semaphore(maxActiveInstanceSize);
        m_finishedTasks = new HashMap<Long, ComputationTask>(
                (int)(4 / 3.0 * maxQueueSize) + 1);
        m_activeTasks = new ConcurrentHashMap<Long, ComputationTask>(
                (int)(4 / 3.0 * maxActiveInstanceSize) + 1);
        m_nextSubmittedIndex = 0;
        m_nextFinishedIndex = 0;
        m_maxQueueSize = maxQueueSize;
        m_maxActiveInstanceSize = maxActiveInstanceSize;
    }

    /** @return Get the number of already submitted tasks (index of the
     * next-to-be-submitted element in the Iterable). Should only be used
     * for statistics.
     */
    public final long getSubmittedCount() {
        return m_nextSubmittedIndex;
    }

    /** @return The number of elements that were already processed by the
     * {@link #processFinished(ComputationTask)} method - used for stats.
     */
    public final long getFinishedCount() {
        return m_nextFinishedIndex;
    }

    /** @return The number of elements currently cached and waiting to be
     * {@link #processFinished(ComputationTask) finally processed}. */
    public final int getFinishedTaskCount() {
        return m_finishedTasks.size();
    }

    /** @return Estimate for number of currently active tasks. */
    public final int getActiveCount() {
        return m_maxActiveInstanceSize
            - m_maxActiveInstanceSemaphore.availablePermits();
    }

    /** Main run method to process the input. This method is to be called only
     * once per instance (subsequent calls will result in an exception).
     *
     * <p>The method will iterate the input, run each element of the argument
     * iterable in its own thread and return when all elements have been
     * processed (when the last computation has passed the
     * {@link #processFinished(ComputationTask)} method).
     *
     * @param inputIterable The input elements.
     * @throws InterruptedException If the main execution has been interrupted
     *          (the thread execution the run method is just delegating work
     *          and will often wait for resources to become available).
     * @throws ExecutionException If an exception is thrown in a worker
     * thread that is not otherwise handled (for instance if
     * {@link #processFinished(ComputationTask)} throws an exception).
     * @throws CancellationException
     * If {@link #cancel(boolean)} has been called.
     */
    public void run(final Iterable<In> inputIterable)
        throws InterruptedException, ExecutionException {
        // run the run method invisibly in the global thread pool
        Callable<Void> c = new Callable<Void>() {
            /** {@inheritDoc} */
            @Override
            public Void call() throws Exception {
                innerRun(inputIterable);
                return null;
            }
        };
        try {
            if (m_executor == null) {
                KNIMEConstants.GLOBAL_THREAD_POOL.runInvisible(c);
            } else {
                c.call();
            }
        } catch (Exception ee) {
            Throwable e = ee.getCause();
            if (e instanceof InterruptedException) {
                throw (InterruptedException)e;
            } else if (e instanceof CancellationException) {
                throw (CancellationException)e;
            } else if (e instanceof ExecutionException) {
                throw (ExecutionException)e;
            }
            throw new ExecutionException(ee);
        }
    }

    /** Actual implementation of the run method.
     * @see #run(Iterable) */
    private void innerRun(final Iterable<In> inputIterable)
        throws InterruptedException, ExecutionException, CancellationException {
        if (m_nextSubmittedIndex > 0L) {
            throw new IllegalStateException("Can only run once");
        }
        m_mainThread = Thread.currentThread();
        final Executor executor = m_executor;
        try {
            for (In in : inputIterable) {
                m_maxActiveInstanceSemaphore.acquire();
                m_maxQueueSemaphore.acquire();
                if (m_isCanceled) {
                    throw new CancellationException();
                }
                try {
                    beforeSubmitting(in, m_nextSubmittedIndex);
                } catch (Exception e) {
                    throw new ExecutionException(e);
                }
                ComputationTask task =
                    new ComputationTask(in, m_nextSubmittedIndex);
                m_activeTasks.put(m_nextSubmittedIndex, task);
                if (executor == null) {
                    KNIMEConstants.GLOBAL_THREAD_POOL.enqueue(task);
                } else {
                    executor.execute(ThreadUtils.runnableWithContext(task));
                }
                m_nextSubmittedIndex += 1L;
            }
            // wait for all jobs to finish
            m_maxQueueSemaphore.acquire(m_maxQueueSize);
        } catch (InterruptedException ie) {
            innerCancel(true);
            if (m_exception == null) {
                throw ie;
            } else {
                // reset interrupted flag that was set when an exception has
                // occurred in callProcessFinished
                Thread.interrupted();
            }
        }
        if (m_exception != null) {
            throw new ExecutionException(m_exception);
        }
        if (m_isCanceled) {
            throw new CancellationException();
        }
    }

    /** @param executor the executor to set (null is the default -- it will
     * then use the global {@link KNIMEConstants#GLOBAL_THREAD_POOL
     * KNIME thread pool}. */
    public void setExecutor(final Executor executor) {
        m_executor = executor;
    }

    /** @return the executor
     * @see #setExecutor(Executor) */
    public Executor getExecutor() {
        return m_executor;
    }

    /** Called from each finishing job (very likely concurrently). */
    private void callProcessFinished(final ComputationTask task) {
        final long index = task.getIndex();
        ComputationTask active = m_activeTasks.remove(index);
        assert active == task : "Task with index " + index
            + " not in active task map";
        try {
            // Attempt to flush output hash. The output is processed
            // sequentially according to the input ordering.
            synchronized (m_finishedTasks) {
                if (m_isCanceled) {
                    // don't processFinished if canceled
                    return;
                }
                // is task next-to-be-processed
                if (index == m_nextFinishedIndex) {
                    ComputationTask first = task;
                    do {
                        try {
                            processFinished(first);
                        } catch (Exception e) {
                            cancel(true);
                            if (e instanceof CancellationException
                                    || e instanceof InterruptedException) {
                                // ordinary cancel
                                m_logger.debug("Cancelling \""
                                        + getClass().getSimpleName()
                                        + "\" due to "
                                        + e.getClass().getSimpleName());
                            } else {
                                // abnormal termination
                                m_exception = e;
                                m_logger.warn("Unhandled exception in "
                                        + "processFinished", e);
                            }
                        } finally {
                            m_maxQueueSemaphore.release();
                        }
                        m_nextFinishedIndex += 1;
                        first = m_finishedTasks.remove(m_nextFinishedIndex);
                        // do while there are more that finished previously
                    } while (first != null);
                } else {
                    // not next-to-be-processed, just line-up
                    ComputationTask nullPrevious =
                        m_finishedTasks.put(index, task);
                    assert nullPrevious == null;
                }
            }
        } finally {
            m_maxActiveInstanceSemaphore.release();
        }
    }

    /** Cancels an ongoing execution.
     * @param mayInterruptIfRunning If working (and the main thread executing
     * the {@link #run(Iterable) run method}) may be interrupted.
     */
    public void cancel(final boolean mayInterruptIfRunning) {
        if (m_mainThread == null) {
            throw new IllegalStateException("Not started");
        }
        m_isCanceled = true;
        innerCancel(mayInterruptIfRunning);
        if (mayInterruptIfRunning) {
            m_mainThread.interrupt();
        }
    }

    private void innerCancel(final boolean mayInterruptIfRunning) {
        for (ComputationTask t : m_activeTasks.values()) {
            t.cancel(mayInterruptIfRunning);
        }
    }

    /** Callback for subclasses to be informed about a new task submission.
     * This method is called iteratively from the {@link #run(Iterable)} method.
     *
     * <p>This default implementation is empty.
     * @param in The element.
     * @param index The index of the element to be submitted.
     * @throws Exception In case the execution shall be aborted
     */
    protected void beforeSubmitting(final In in, final long index)
        throws Exception {
        // subclass hook
    }

    /** Performs the computation for a given input. This method is called
     * concurrently for different input records.
     * @param in The element.
     * @param index The index of the element.
     * @return The computed output
     * @throws Exception Any exception, to be handled in the
     * {@link #processFinished(ComputationTask)} implementation
     * (more specifically in {@link ComputationTask#get()}.
     */
    protected abstract Out compute(In in, final long index) throws Exception;

    /** Post-process a finished computation, for instance write a computed
     * result into a file or add a computed row to a data container. This method
     * is <b>not called concurrently</b> and the passed {@link ComputationTask}
     * objects come in the order represented by the iterator of the
     * {@link #run(Iterable)} method.
     *
     * <p>The result of a computation is to be retrieved using the task's
     * {@linkplain ComputationTask#get() get} method. The implementation may
     * want to handle any exception that is possibly thrown by the
     * {@link #compute(Object, long) computation} (for instance
     * by logging an error and replacing the result with an appropriate missing
     * value) -- if it's not handled the entire execution will abort with an
     * error being logged.
     *
     * @param task The next task to be finally processed.
     * @throws ExecutionException If the exception of the Computation is no
     * further handled -- and causes the entire calculation to stop.
     * @throws CancellationException If canceled (abort)
     * @throws InterruptedException If canceled (abort)
     */
    protected abstract void processFinished(ComputationTask task)
    throws ExecutionException, CancellationException, InterruptedException;


    /** Represents a single computation, consists of corresponding input record,
     * input index and the computed output. The output is to be retrieved using
     * the {@link #get()} method.
     */
    public final class ComputationTask extends FutureTask<Out> {

        private final In m_in;
        private final long m_index;

        private ComputationTask(final In in, final long index) {
            super(new Callable<Out>() {
                /** {@inheritDoc} */
                @Override
                public Out call() throws Exception {
                    return MultiThreadWorker.this.compute(in, index);
                }
            });
            m_in = in;
            m_index = index;
        }

        /** @return the input */
        public In getInput() {
            return m_in;
        }

        /** @return the index */
        public long getIndex() {
            return m_index;
        }

        /** {@inheritDoc} */
        @Override
        protected void done() {
            callProcessFinished(this);
        }
    }

}
