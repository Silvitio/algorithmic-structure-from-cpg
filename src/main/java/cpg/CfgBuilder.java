package cpg;

import analysismodel.BranchArm;
import analysismodel.CfgEdge;
import analysismodel.CfgEdgeKind;
import analysismodel.FunctionModel;
import analysismodel.IfStructure;
import analysismodel.LoopStructure;
import analysismodel.NodeKind;
import analysismodel.ProgramNode;
import analysismodel.Region;
import analysismodel.SwitchStructure;

import java.util.ArrayList;
import java.util.List;

public final class CfgBuilder {
    public void build(FunctionModel model) {
        Fragment bodyFragment = buildRegion(model, model.bodyRegion());
        if (bodyFragment.entry() != null) {
            connect(model.entry(), bodyFragment.entry(), CfgEdgeKind.NEXT);
            connectPending(bodyFragment.pendingExits(), model.exit());
        } else {
            connect(model.entry(), model.exit(), CfgEdgeKind.NEXT);
        }
    }

    private Fragment buildRegion(FunctionModel model, Region region) {
        ProgramNode firstEntry = null;
        List<PendingExit> pendingExits = new ArrayList<>();

        for (String nodeId : region.nodeIds()) {
            ProgramNode node = model.findByCpgNodeId(nodeId).orElse(null);
            if (node == null) {
                continue;
            }

            Fragment fragment = buildNode(model, node);
            if (fragment.entry() == null) {
                continue;
            }

            if (firstEntry == null) {
                firstEntry = fragment.entry();
            }

            if (!pendingExits.isEmpty()) {
                connectPending(pendingExits, fragment.entry());
            }

            pendingExits = new ArrayList<>(fragment.pendingExits());
        }

        return new Fragment(firstEntry, pendingExits);
    }

    private Fragment buildNode(FunctionModel model, ProgramNode node) {
        if (node.kind() == NodeKind.TRANSFER) {
            connect(node, model.exit(), CfgEdgeKind.TRANSFER);
            return new Fragment(node, List.of());
        }

        IfStructure ifStructure = model.ifStructures().get(node.cpgNodeId());
        if (ifStructure != null) {
            return buildIf(model, node, ifStructure);
        }

        SwitchStructure switchStructure = model.switchStructures().get(node.cpgNodeId());
        if (switchStructure != null) {
            return buildSwitch(model, node, switchStructure);
        }

        LoopStructure loopStructure = model.loopStructures().get(node.cpgNodeId());
        if (loopStructure != null) {
            return buildLoop(model, node, loopStructure);
        }

        return new Fragment(node, List.of(new PendingExit(node, CfgEdgeKind.NEXT)));
    }

    private Fragment buildIf(FunctionModel model, ProgramNode owner, IfStructure structure) {
        Fragment thenFragment = buildRegion(model, structure.thenRegion());
        Fragment elseFragment = buildRegion(model, structure.elseRegion());
        List<PendingExit> pendingExits = new ArrayList<>();

        if (thenFragment.entry() != null) {
            connect(owner, thenFragment.entry(), CfgEdgeKind.TRUE_BRANCH);
            pendingExits.addAll(thenFragment.pendingExits());
        } else {
            pendingExits.add(new PendingExit(owner, CfgEdgeKind.TRUE_BRANCH));
        }

        if (elseFragment.entry() != null) {
            connect(owner, elseFragment.entry(), CfgEdgeKind.FALSE_BRANCH);
            pendingExits.addAll(elseFragment.pendingExits());
        } else {
            pendingExits.add(new PendingExit(owner, CfgEdgeKind.FALSE_BRANCH));
        }

        return new Fragment(owner, pendingExits);
    }

    private Fragment buildSwitch(FunctionModel model, ProgramNode owner, SwitchStructure structure) {
        List<PendingExit> pendingExits = new ArrayList<>();

        for (BranchArm arm : structure.arms()) {
            Fragment armFragment = buildRegion(model, arm.body());
            CfgEdgeKind edgeKind = arm.isDefault() ? CfgEdgeKind.DEFAULT_BRANCH : CfgEdgeKind.CASE_BRANCH;

            if (armFragment.entry() != null) {
                connect(owner, armFragment.entry(), edgeKind);
                pendingExits.addAll(armFragment.pendingExits());
            } else {
                pendingExits.add(new PendingExit(owner, edgeKind));
            }
        }

        if (structure.arms().isEmpty()) {
            pendingExits.add(new PendingExit(owner, CfgEdgeKind.NEXT));
        }

        return new Fragment(owner, pendingExits);
    }

    private Fragment buildLoop(FunctionModel model, ProgramNode owner, LoopStructure structure) {
        Fragment initializerFragment = buildRegion(model, structure.initializerRegion());
        Fragment bodyFragment = buildRegion(model, structure.bodyRegion());
        Fragment iterationFragment = buildRegion(model, structure.iterationRegion());

        ProgramNode entry = owner;
        if (initializerFragment.entry() != null) {
            entry = initializerFragment.entry();
            connectPending(initializerFragment.pendingExits(), owner);
        }

        ProgramNode trueTarget = firstNonNull(bodyFragment.entry(), iterationFragment.entry(), owner);
        connect(owner, trueTarget, CfgEdgeKind.TRUE_BRANCH);

        if (bodyFragment.entry() != null) {
            if (iterationFragment.entry() != null) {
                connectPending(bodyFragment.pendingExits(), iterationFragment.entry());
                connectPending(iterationFragment.pendingExits(), owner, CfgEdgeKind.LOOP_BACK);
            } else {
                connectPending(bodyFragment.pendingExits(), owner, CfgEdgeKind.LOOP_BACK);
            }
        } else if (iterationFragment.entry() != null) {
            connectPending(iterationFragment.pendingExits(), owner, CfgEdgeKind.LOOP_BACK);
        }

        List<PendingExit> pendingExits = List.of(new PendingExit(owner, CfgEdgeKind.FALSE_BRANCH));
        return new Fragment(entry, pendingExits);
    }

    private ProgramNode firstNonNull(ProgramNode first, ProgramNode second, ProgramNode fallback) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        return fallback;
    }

    private void connectPending(List<PendingExit> pendingExits, ProgramNode target) {
        connectPending(pendingExits, target, null);
    }

    private void connectPending(List<PendingExit> pendingExits, ProgramNode target, CfgEdgeKind overrideKind) {
        for (PendingExit pendingExit : pendingExits) {
            connect(pendingExit.from(), target, overrideKind == null ? pendingExit.kind() : overrideKind);
        }
    }

    private void connect(ProgramNode from, ProgramNode to, CfgEdgeKind kind) {
        CfgEdge edge = new CfgEdge(from, to, kind);
        from.addOutgoingEdge(edge);
        to.addIncomingEdge(edge);
    }

    private record Fragment(ProgramNode entry, List<PendingExit> pendingExits) {
    }

    private record PendingExit(ProgramNode from, CfgEdgeKind kind) {
    }
}
