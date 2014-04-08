package com.cmpe283.project1;

import java.io.IOException;

import java.net.MalformedURLException;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;


import com.vmware.vim25.AlarmSetting;
import com.vmware.vim25.AlarmSpec;
import com.vmware.vim25.FileFault;
import com.vmware.vim25.InsufficientResourcesFault;
import com.vmware.vim25.InvalidProperty;
import com.vmware.vim25.InvalidState;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.RuntimeFault;
import com.vmware.vim25.StateAlarmExpression;
import com.vmware.vim25.StateAlarmOperator;
import com.vmware.vim25.TaskInProgress;
import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VirtualMachineSnapshotInfo;
import com.vmware.vim25.VirtualMachineSnapshotTree;
import com.vmware.vim25.VmConfigFault;
import com.vmware.vim25.mo.Alarm;
import com.vmware.vim25.mo.AlarmManager;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.HostSystem;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;


public class vmMangment {
	    private static String [] hostNames = {"130.65.133.41", "130.65.133.42"};
	    private static HashMap<String, String> source = new HashMap<String,String>();
	    private static ServiceInstance si;
		private static ServiceInstance si1;
		private volatile static Thread snapshotRun;
		private volatile static Thread checkStautsRun;
		private static CountDownLatch latch = new CountDownLatch(1);
		//cmd ping the vm or host
		public static boolean pingIp(String ipAddress) throws IOException, InterruptedException{
	        String cmd = "ping " + ipAddress;
	        Runtime runTime = Runtime.getRuntime();
	        Process process = runTime.exec(cmd);
	        process.waitFor();
	        return process.exitValue()==0;
	    }
	    
		//get list of vm
		public static ArrayList<VirtualMachine> getListVms(ServiceInstance si,String vmname) throws InvalidProperty, RuntimeFault, RemoteException, MalformedURLException{
			HostSystem hs= hostByname(vmname,si);
			//System.out.println(hs.getName());
			Folder rootFolder = si.getRootFolder();
			String name = rootFolder.getName();
			System.out.println("root:" + name);
			System.out.println("====================================================================");
			ArrayList<VirtualMachine> vms = new ArrayList<VirtualMachine>();
			ManagedEntity[] mes = new InventoryNavigator(hs).searchManagedEntities(new String[][] { {"VirtualMachine", "name" }, }, true);
			for(int i=0;i<mes.length;i++){
				vms.add((VirtualMachine)mes[i]);
			}
			return vms;
			
		}
		//check if alarm set
		public static boolean ifPwrOffAlertset(VirtualMachine vm, AlarmManager am)throws IOException, InterruptedException{
			Alarm[] alarms = am.getAlarm(vm);
			boolean setAlarm = false;
			for(Alarm alarm:alarms){
			if(alarm.getAlarmInfo().name.equals("Pwr_Off_vm "+vm.getName())){setAlarm = true;}
			}
			return setAlarm;
		}

		
		//create alarm
		public static void createPwrOffAlarm(VirtualMachine vm, AlarmManager am) throws IOException, InterruptedException{
			if(ifPwrOffAlertset(vm,am)==false){
				AlarmSpec spec = new AlarmSpec();
				StateAlarmExpression expression = new StateAlarmExpression();
				AlarmSetting as = new AlarmSetting();
				
				expression.setType("VirtualMachine");
				expression.setStatePath("runtime.powerState");
				expression.setOperator(StateAlarmOperator.isEqual);
				expression.setRed("poweredOff");
				as.setReportingFrequency(0);
				as.setToleranceRange(0);
				spec.setName("Pwr_Off_vm " + vm.getName()); 
				spec.setDescription(vm.getName());
				spec.setExpression(expression);
				spec.setSetting(as);
				spec.setEnabled(true);
				am.createAlarm(vm, spec);
				System.out.println("Set Power off alarm");
				}
			else{System.out.println("Power off alarm already set");}
		}
		
	
		//power on vm
		public static void turnOnVm(VirtualMachine vm) throws VmConfigFault, TaskInProgress, FileFault, InvalidState, InsufficientResourcesFault, RuntimeFault, RemoteException, InterruptedException{
			
			if(vm.getRuntime().getPowerState() == VirtualMachinePowerState.poweredOff){
				System.out.println("trun on " + vm.getName());
				Task task = vm.powerOnVM_Task(null);
				if(task.waitForTask() == Task.SUCCESS){System.out.println("Powered on " + vm.getName());}
				else{System.out.println("Power on a " + vm.getName() + "failed");}
			}
			else{System.out.println(vm.getName() + " already Powered on");}
		}
		
		//vm by name
		public static VirtualMachine vmbyName(String vmname,ServiceInstance si) throws InvalidProperty, RuntimeFault, RemoteException{
			 Folder rootFolder = si.getRootFolder();
			 VirtualMachine vm = (VirtualMachine) new InventoryNavigator(
			      rootFolder).searchManagedEntity("VirtualMachine", vmname);
			 return vm;
		}
		//op snapshot create revert removeall remove
		public static void snapshotOp(String vmname,String op,ServiceInstance si) throws InvalidProperty, RuntimeFault, RemoteException, InterruptedException{
			
			String snapshotname = vmname + "snapshot";
			String desc = snapshotname + "desc";
			boolean removechild = true;
			VirtualMachine vm = vmbyName(vmname,si);
		    if("create".equalsIgnoreCase(op))
		    {
		      System.out.println("creating snapshot for "+ vmname );
		      Task task = vm.createSnapshot_Task(
		          snapshotname, desc, false, false);
		      if(task.waitForTask()==Task.SUCCESS)
		      {
		        System.out.println("Snapshot was created. "+ vmname );
		        System.out.println("====================================================================");
		      }
		    }
		    else if("list".equalsIgnoreCase(op))
		    {
		      listSnapshots(vm);
		    }
		    else if(op.equalsIgnoreCase("revert")) 
		    {
		      VirtualMachineSnapshot vmsnap = getSnapshotInTree(
		          vm, snapshotname);
		      if(vmsnap!=null)
		      {
		    	System.out.println("reverting snapshot for "+ vmname );
		        Task task = vmsnap.revertToSnapshot_Task(null);
		        if(task.waitForTask()==Task.SUCCESS)
		        {
		          System.out.println("Reverted to snapshot:" 
		              + snapshotname);
		        }
		      }
		    }
		    else if(op.equalsIgnoreCase("removeall")) 
		    {
		      Task task = vm.removeAllSnapshots_Task();      
		      if(task.waitForTask()== Task.SUCCESS) 
		      {
		        System.out.println("Removed all snapshots");
		      }
		    }
		    else if(op.equalsIgnoreCase("remove")) 
		    {
		      VirtualMachineSnapshot vmsnap = getSnapshotInTree(
		          vm, snapshotname);
		      if(vmsnap!=null)
		      {
		        Task task = vmsnap.removeSnapshot_Task(removechild);
		        if(task.waitForTask()==Task.SUCCESS)
		        {
		          System.out.println("Removed snapshot:" + snapshotname);
		        }
		      }
		    }
		    else 
		    {
		      System.out.println("Invalid operation");
		      return;
		    }
		  }
		  
		  static VirtualMachineSnapshot getSnapshotInTree(
		      VirtualMachine vm, String snapName)
		  {
		    if (vm == null || snapName == null) 
		    {
		      return null;
		    }

		    VirtualMachineSnapshotTree[] snapTree = 
		        vm.getSnapshot().getRootSnapshotList();
		    if(snapTree!=null)
		    {
		      ManagedObjectReference mor = findSnapshotInTree(
		          snapTree, snapName);
		      if(mor!=null)
		      {
		        return new VirtualMachineSnapshot(
		            vm.getServerConnection(), mor);
		      }
		    }
		    return null;
		  }

		  static ManagedObjectReference findSnapshotInTree(
		      VirtualMachineSnapshotTree[] snapTree, String snapName)
		  {
		    for(int i=0; i <snapTree.length; i++) 
		    {
		      VirtualMachineSnapshotTree node = snapTree[i];
		      if(snapName.equals(node.getName()))
		      {
		        return node.getSnapshot();
		      } 
		      else 
		      {
		        VirtualMachineSnapshotTree[] childTree = 
		            node.getChildSnapshotList();
		        if(childTree!=null)
		        {
		          ManagedObjectReference mor = findSnapshotInTree(
		              childTree, snapName);
		          if(mor!=null)
		          {
		            return mor;
		          }
		        }
		      }
		    }
		    return null;
		  }

		  static void listSnapshots(VirtualMachine vm)
		  {
		    if(vm==null)
		    {
		      return;
		    }
		    VirtualMachineSnapshotInfo snapInfo = vm.getSnapshot();
		    VirtualMachineSnapshotTree[] snapTree = 
		      snapInfo.getRootSnapshotList();
		    printSnapshots(snapTree);
		  }

		  static void printSnapshots(
		      VirtualMachineSnapshotTree[] snapTree)
		  {
		    for (int i = 0; snapTree!=null && i < snapTree.length; i++) 
		    {
		      VirtualMachineSnapshotTree node = snapTree[i];
		      System.out.println("Snapshot Name : " + node.getName());           
		      VirtualMachineSnapshotTree[] childTree = 
		        node.getChildSnapshotList();
		      if(childTree!=null)
		      {
		        printSnapshots(childTree);
		      }
		    }
		  }
		
		//get list of host
		public static HostSystem hostByname(String vmname, ServiceInstance si) throws InvalidProperty, RuntimeFault, RemoteException{
				Folder rootFolder = si.getRootFolder();
				HostSystem vhs = (HostSystem) new InventoryNavigator(rootFolder).searchManagedEntity("HostSystem", vmname);
				return vhs;
			}
	
		//recovery vm host
		public static void recoveryHost(ServiceInstance si,String vmname) throws IOException, InterruptedException{
		
				 Folder rootFolder = si.getRootFolder();
				 VirtualMachine vm = (VirtualMachine) new InventoryNavigator(
				      rootFolder).searchManagedEntity("VirtualMachine", vmname);
				      snapshotOp(vm.getName(),"revert",si);
					  turnOnVm(vm);	
		
		}
	    //ifPwrOff
		public static boolean ifPwrOff(String vmname,ServiceInstance si) throws InvalidProperty, RuntimeFault, RemoteException{
			boolean b = false;
			if(vmbyName(vmname,si).getRuntime().getPowerState()==VirtualMachinePowerState.poweredOff){b=true;}
			return b;
		}
		
		
		//remove host
		
	
		
		//vm info
		public static void printVMinfo(VirtualMachine vm) throws IOException, InterruptedException{
			
				vm.getResourcePool();
				System.out.println("Hello " + vm.getName());
				System.out.println("Status " + vm.getGuestHeartbeatStatus());
				System.out.println("get ip "+ vm.getSummary().getGuest().getIpAddress());
				System.out.println("get id "+ vm.getSummary().getGuest().getGuestId());
				System.out.println("get toolstatus "+ vm.getSummary().getGuest().toolsRunningStatus);
				System.out.println("get hostname "+ vm.getSummary().getGuest().getHostName());
				System.out.println("GuestOS: " + vm.getConfig().getGuestFullName());
				System.out.println("vm version: " + vm.getConfig().version);
				System.out.println("meomery: " + vm.getConfig().getHardware().memoryMB + "MB");
				//System.out.println("meomery overhead: " + vm.getConfig().memoryAllocation.reservation.toString() + "MB");
				System.out.println("cpu: " + vm.getConfig().getHardware().numCPU);
				System.out.println("Multiple snapshot supported: " + vm.getCapability().isMultipleSnapshotsSupported());
				System.out.println("====================================================================");
			}
		
		//recovery vm
		public static void recoveryVm(ServiceInstance si,String hostname) throws VmConfigFault, TaskInProgress, FileFault, InvalidState, InsufficientResourcesFault, InvalidProperty, RuntimeFault, RemoteException, IOException, InterruptedException{
			
			for(VirtualMachine vm :getListVms(si,hostname)){
				//System.out.println(vm.getSummary().getGuest().getIpAddress());
				if(!vm.getSummary().overallStatus.toString().equals("red")){
				System.out.println("ping" + vm.getName());
				  if(!pingIp(vm.getSummary().getGuest().getIpAddress())){
					  System.out.println("dead " + vm.getName());
					  snapshotOp(vm.getName(),"revert",si);
					  turnOnVm(vm);
					  }
				 }
				else{System.out.println("ping" + vm.getName()+ " ok");}
				
			}
		}
		//Vcenter
		public static void vCenter(ServiceInstance si,String hostname) throws IOException, InterruptedException{
			
			AlarmManager am = si.getAlarmManager();
			//ping host
			//for(String hostName:getHostsName(si)){
			//if(pingIp(hostName)){System.out.println("vhost " + hostName + " ok");}
			//}
			
			for(VirtualMachine vm :getListVms(si,hostname)){
				
				turnOnVm(vm);
				printVMinfo(vm);
				createPwrOffAlarm(vm,am);
				snapshotOp(vm.getName(),"create",si);
			}
			//si.getServerConnection().logout();
		}
		
		public static void setvmMangment() throws RemoteException, MalformedURLException{
			si1 = new ServiceInstance(new URL("https://130.65.132.13/sdk"), "administrator", "12!@qwQW", true);
		    si = new ServiceInstance(new URL("https://130.65.132.116/sdk"), "administrator", "12!@qwQW", true);
			source.put("130.65.133.41", "t16-vHost01-cum3-lab1 _.133.41");
			source.put("130.65.133.42","t16-vHost02-cum3-lab2 _.133.42");
			
		}
		
		public static void vCenterInitialization() throws InterruptedException, IOException{
			setvmMangment();
			 for(String vmname:hostNames){
				vCenter(si,vmname);
		        snapshotOp(source.get(vmname),"create",si1);
		   }
		    System.out.println("Finished Initialization!!!");
		}
		
		public static void startThreads() throws RemoteException, MalformedURLException{
			setvmMangment();
			System.out.println("Strating running thread!!!");
			snapshot st = new snapshot();
		    snapshotRun =  new Thread(st);
		    ckStatus ck = new ckStatus();
		    checkStautsRun = new Thread(ck);
		   
		    snapshotRun.start();
		    checkStautsRun.start();
		    
		}
		
	
		
		public static class snapshot implements Runnable{
			@Override
			public void run(){
			while(true){
				try {
					latch.await();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				for(String vmname:hostNames){
			    	  try {
						for(VirtualMachine vm :getListVms(si,vmname)){
								try {
									if(pingIp(vm.getSummary().getGuest().getIpAddress())){
										snapshotOp(vm.getName(),"create",si);
									}
								} catch (IOException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								}
							}
					} catch (InvalidProperty e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					} catch (RuntimeFault e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					} catch (RemoteException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					} catch (MalformedURLException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					} catch (InterruptedException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
						
				    try {
				    	try {
							if(pingIp(vmname)){
							snapshotOp(source.get(vmname),"create",si1);
							}
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				  }
			 
			try {
			    Thread.sleep(36000);
				} catch (InterruptedException e) {
			    e.printStackTrace();
				}
			
		}
			
			}
}
		public static class ckStatus implements Runnable{
			@Override
			public void run(){
				while(true){
				for(String vmname:hostNames){
			    	
			    	    try {
			    	    	
							if(!ifPwrOff(source.get(vmname),si1))
							{
								System.out.println("ping "+source.get(vmname));
								if(!pingIp(vmname)){
      
								System.out.println("dead " +source.get(vmname));
								recoveryHost(si1,source.get(vmname));
								}
								else{recoveryVm(si,vmname);}
								
							}
							
						
							
							
						} catch (VmConfigFault e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (TaskInProgress e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (FileFault e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (InvalidState e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (InsufficientResourcesFault e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (InvalidProperty e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (RuntimeFault e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (RemoteException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
			    }
				/*try {
					recoveryVm(si);
				} catch (VmConfigFault e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} catch (TaskInProgress e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} catch (FileFault e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} catch (InvalidState e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} catch (InsufficientResourcesFault e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} catch (InvalidProperty e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} catch (RuntimeFault e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} catch (RemoteException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} catch (IOException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} catch (InterruptedException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}*/
			
			try {
			    Thread.sleep(36000);
				} catch (InterruptedException e) {
			    e.printStackTrace();
				}
			latch.countDown();
				}
		}
	}

		
}


		
		    

		


