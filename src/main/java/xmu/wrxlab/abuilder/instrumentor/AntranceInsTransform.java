package xmu.wrxlab.abuilder.instrumentor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import soot.*;
import soot.jimple.*;
import soot.jimple.internal.JArrayRef;
import soot.jimple.internal.JAssignStmt;
import soot.tagkit.LineNumberTag;
import soot.javaToJimple.DefaultLocalGenerator;

// 插桩
public class AntranceInsTransform {
    /**
     * Instrumentor传入
     */
    private String database;
    /**
     * Instrumentor传入
     */
    private String projectId;
    /**
     * Instrumentor传入
     */
    private Map<Integer, String> logIdSig;
    /**
     * AntranceIns: int[] StmtTable
     */
    private SootField stmtTable;
    private SootMethod setStmtTable2;
    /**
     * JimpleLocal r = staticFieldRef AntranceIns: int[] StmtTable
     */
    private AssignStmt r_StmtTable;
    /**
     * JArrayRef r[idx] = IntConstant
     */
    private AssignStmt r_idx_int;
    private Stmt setStmtTable2Stmt;

    private final ArrayList<SootClass> myClasses;

    /**
     * 临时空目标.
     * 一定要注意insertBefore会改变其他target目标
     * (从设计者的角度理解, 如果不这样设计的话用户想正确触发before函数还需要自己手动修改)
     * 因此想要正确实现我们的目标可以在结构稳定后再设置target, 或者用那个insertBeforeNoRedirect
     */
    private final NopStmt nopStmt;

    public AntranceInsTransform(ArrayList<SootClass> myClasses, SootClass antranceIns,
                                String database, String projectId,
                                Map<Integer, String> logIdSig) {
        this.database = database;
        this.projectId = projectId;
        this.logIdSig = logIdSig;
        stmtTable = antranceIns.getFieldByName("stmtTable");
        setStmtTable2 = antranceIns.getMethodByName("setStmtTable2");
        this.myClasses = myClasses;
        nopStmt = Jimple.v().newNopStmt();
    }

    private String calMethodSig(SootMethod curMethod) {
        String methodSig = curMethod.getSignature(); // 函数唯一标识
        // 避免一些html转义问题
        methodSig = methodSig.replace("<", "[");
        methodSig = methodSig.replace(">", "]");
        return methodSig;
    }

    public void start() {
        for (SootClass curClass : myClasses) {
            for (SootMethod curMethod : curClass.getMethods()) {
                String methodSig = calMethodSig(curMethod);
                if (!curMethod.hasActiveBody()) continue;
                Body body = curMethod.getActiveBody();
                if (body.getUnits().isEmpty()) continue;
                PatchingChain<Unit> units = body.getUnits();
                Iterator<Unit> stmtIt = units.snapshotIterator();
                int jid = 0; // 用jid唯一标识函数内的每条语句
                int flag = 0; // 用来找第一条非identify @this/@parameter语句,在前面插入enterMethod
                while (stmtIt.hasNext()) {
                    Stmt stmt = (Stmt) stmtIt.next();
                    if (flag == 0 && !(stmt instanceof IdentityStmt)) {
                        flag = 1;
                        calAssign2(body, methodSig);
                        units.insertBeforeNoRedirect(r_StmtTable, stmt);
                        units.insertBeforeNoRedirect(r_idx_int, stmt);
                        units.insertBeforeNoRedirect(setStmtTable2Stmt, stmt);
                    }
                    if (stmt instanceof IfStmt) {
                        visitIfStmt(body, methodSig, jid, units, (IfStmt) stmt);
                    } else if (stmt instanceof LookupSwitchStmt) {
                        visitLookupSwitchStmt(body, methodSig, jid, units, (LookupSwitchStmt) stmt);
                    } else if (stmt instanceof TableSwitchStmt) {
                        visitTableSwitchStmt(body, methodSig, jid, units, (TableSwitchStmt) stmt);
                    }
                    jid++;
                } // end of while stmtIt.hasNext()
            } // end of while methodIterator.hasNext()
        } // end of for curClass
        // 将logIdSig输出到项目目录的logIdSig.txt中
        try {
            BufferedWriter out = new BufferedWriter(new FileWriter(
                    new File(database+"/"+projectId, "logIdSig.txt")));
            for (Map.Entry<Integer, String> entry : logIdSig.entrySet()) {
                out.write(entry.getKey() + " " + entry.getValue() + "\n");
            }
            out.flush();
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("antrance ins error");
        }
    } // end of start

    private void visitIfStmt(Body body, String methodSig, int jid, PatchingChain<Unit> units, IfStmt stmt) {
        // 1: if condition false goto 3
        // 2: condition true stmt
        // 3: condition false stmt
        // to
        // 1: if condition false goto 5
        // 2: [ins br 1]
        // 3: condition true stmt
        // 4: goto 6
        // 5: [ins br 0]
        // 6: condition false stmt

        // if true
        calAssign2(body, methodSig + "@" + jid + "@" + getLineNumber(stmt) + "@br@1");
        units.insertAfter(r_idx_int, stmt);
        units.insertAfter(r_StmtTable, stmt);
        units.insertAfter(setStmtTable2Stmt, stmt);
        // if false
        // 修改target+goto阻断上方语句
        GotoStmt gotoStmt = Jimple.v().newGotoStmt(nopStmt);
        calAssign2(body, methodSig + "@" + jid + "@" + getLineNumber(stmt) + "@br@0");
        // 记录原目标
        Stmt target = stmt.getTarget();
        // 在原目标上方依次插入goto, r_StmtTable, r_idx_int
        units.insertBeforeNoRedirect(gotoStmt, target);
        units.insertBeforeNoRedirect(r_StmtTable, target);
        units.insertBeforeNoRedirect(r_idx_int, target);
        units.insertBeforeNoRedirect(setStmtTable2Stmt, target);
        // goto指向原target
        // 其实有noredirecrt可以直接在构造时设置target, 这里算是双保险吧
        gotoStmt.setTarget(target);
        // if false target指向r_StmtTable
        stmt.setTarget(r_StmtTable);
    }

    private void visitLookupSwitchStmt(Body body, String methodSig, int jid, PatchingChain<Unit> units, LookupSwitchStmt stmt) {
        // cases
        for (int i = 0; i < stmt.getTargetCount(); i++) {
            Unit target = stmt.getTarget(i);

            calAssign2(body, methodSig + "@" + jid + "@" + getLineNumber(stmt) + "@br@" + (i + 1));
            units.insertBeforeNoRedirect(r_StmtTable, target);
            units.insertBeforeNoRedirect(r_idx_int, target);
            units.insertBeforeNoRedirect(setStmtTable2Stmt, target);
            stmt.setTarget(i, r_StmtTable);
        }
        // default不需要goto阻断, 因为switch结束一个case一定会显示使用break
        Unit target = stmt.getDefaultTarget();
        calAssign2(body, methodSig + "@" + jid + "@" + getLineNumber(stmt) + "@br@0");
        units.insertBeforeNoRedirect(r_StmtTable, target);
        units.insertBeforeNoRedirect(r_idx_int, target);
        units.insertBeforeNoRedirect(setStmtTable2Stmt, target);
        stmt.setDefaultTarget(r_StmtTable);
    }

    private void visitTableSwitchStmt(Body body, String methodSig, int jid, PatchingChain<Unit> units, TableSwitchStmt stmt) {
        // cases
        List<Unit> targets = stmt.getTargets();
        for (int i = 0; i < targets.size(); i++) {
            Unit target = targets.get(i);

            calAssign2(body, methodSig + "@" + jid + "@" + getLineNumber(stmt) + "@br@" + (i + 1));
            units.insertBeforeNoRedirect(r_StmtTable, target);
            units.insertBeforeNoRedirect(r_idx_int, target);
            units.insertBeforeNoRedirect(setStmtTable2Stmt, target);
            stmt.setTarget(i, r_StmtTable);
        }
        // default不需要goto阻断, 因为switch结束一个case一定会显示使用break
        Unit target = stmt.getDefaultTarget();
        calAssign2(body, methodSig + "@" + jid + "@" + getLineNumber(stmt) + "@br@0");
        units.insertBeforeNoRedirect(r_StmtTable, target);
        units.insertBeforeNoRedirect(r_idx_int, target);
        units.insertBeforeNoRedirect(setStmtTable2Stmt, target);
        stmt.setDefaultTarget(r_StmtTable);
    }


    /**
     * 计算r_StmtTable, r_idx_int, 更新logIdSig, 其中id是根据logIdSig的size计算得到的
     *
     * @param body 为了generateFreshLocal传个body
     * @param sig  logIdSig的sig
     */
    private void calAssign2(Body body, String sig) {
        int idx = logIdSig.size();
        logIdSig.put(idx, sig);
        Local r = generateFreshLocal(body, ArrayType.v(IntType.v(), 1));
        r_StmtTable = new JAssignStmt(r, Jimple.v().newStaticFieldRef(stmtTable.makeRef()));
        r_idx_int = new JAssignStmt(new JArrayRef(r, IntConstant.v(idx)), IntConstant.v(1));
        InvokeExpr setStmtTable2Expr = Jimple.v().newStaticInvokeExpr(setStmtTable2.makeRef(),
                IntConstant.v(idx));
        setStmtTable2Stmt = Jimple.v().newInvokeStmt(setStmtTable2Expr);
    }

    /**
     * 在body中创建一个新local
     *
     * @param body local所属的body
     * @param type local的type
     * @return 新建local
     */
    public Local generateFreshLocal(Body body, Type type) {
        LocalGenerator lg = new DefaultLocalGenerator(body);
        return lg.generateLocal(type);
    }

    private int getLineNumber(Stmt s) {
        for (Object o : s.getTags()) {
            if (o instanceof LineNumberTag) {
                return Integer.parseInt(o.toString());
            }
        }
        return 0;
    }
}
