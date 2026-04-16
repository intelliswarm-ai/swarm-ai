package ai.intelliswarm.swarmai.event;

import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import org.springframework.context.ApplicationEvent;

/**
 * Published once by {@code Swarm.kickoff()} after a swarm run finishes
 * successfully. Carries the full {@link SwarmOutput} plus the
 * {@link BudgetTracker} so downstream listeners (notably the
 * self-improvement module) can build an execution trace and run their
 * post-workflow analysis without reaching back into the swarm.
 *
 * <p>This is separate from the lightweight {@link SwarmEvent} with
 * {@link SwarmEvent.Type#SWARM_COMPLETED} so existing listeners that only
 * care about status transitions keep working unchanged.
 */
public class SwarmCompletedEvent extends ApplicationEvent {

    private final String swarmId;
    private final SwarmOutput output;
    private final BudgetTracker budgetTracker;

    public SwarmCompletedEvent(Object source,
                               String swarmId,
                               SwarmOutput output,
                               BudgetTracker budgetTracker) {
        super(source);
        this.swarmId = swarmId;
        this.output = output;
        this.budgetTracker = budgetTracker;
    }

    public String getSwarmId() {
        return swarmId;
    }

    public SwarmOutput getOutput() {
        return output;
    }

    public BudgetTracker getBudgetTracker() {
        return budgetTracker;
    }
}
