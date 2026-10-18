package dev.autonomousvillages;

/** Carries everything the worker does not keep to the village store. */
final class DepositJob extends Job {
    private final Deposit deposit=new Deposit();
    DepositJob(Worker w){super(w,"несёт добычу на склад");}
    @Override String kind(){return "deposit";}
    @Override boolean tick(){
        return switch(deposit.tick()){
            case ARRIVED->{v.stock.invalidate();yield false;}
            case FAILED->fail("склад полон или недоступен");
            default->true;
        };
    }
    @Override void stop(){deposit.cancel();}
}
