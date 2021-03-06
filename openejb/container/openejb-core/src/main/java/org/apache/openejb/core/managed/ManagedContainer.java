/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openejb.core.managed;

import org.apache.openejb.ApplicationException;
import org.apache.openejb.BeanContext;
import org.apache.openejb.ContainerType;
import org.apache.openejb.InterfaceType;
import org.apache.openejb.InvalidateReferenceException;
import org.apache.openejb.OpenEJBException;
import org.apache.openejb.OpenEJBRuntimeException;
import org.apache.openejb.ProxyInfo;
import org.apache.openejb.RpcContainer;
import org.apache.openejb.SystemException;
import org.apache.openejb.core.ExceptionType;
import org.apache.openejb.core.InstanceContext;
import org.apache.openejb.core.Operation;
import org.apache.openejb.core.ThreadContext;
import org.apache.openejb.core.interceptor.InterceptorData;
import org.apache.openejb.core.interceptor.InterceptorStack;
import org.apache.openejb.core.managed.Cache.CacheFilter;
import org.apache.openejb.core.managed.Cache.CacheListener;
import org.apache.openejb.core.transaction.BeanTransactionPolicy;
import org.apache.openejb.core.transaction.BeanTransactionPolicy.SuspendedTransaction;
import org.apache.openejb.core.transaction.EjbTransactionUtil;
import org.apache.openejb.core.transaction.EjbUserTransaction;
import org.apache.openejb.core.transaction.JtaTransactionPolicy;
import org.apache.openejb.core.transaction.TransactionPolicy;
import org.apache.openejb.core.transaction.TransactionPolicy.TransactionSynchronization;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.monitoring.LocalMBeanServer;
import org.apache.openejb.monitoring.ManagedMBean;
import org.apache.openejb.monitoring.ObjectNameBuilder;
import org.apache.openejb.monitoring.StatsInterceptor;
import org.apache.openejb.persistence.EntityManagerAlreadyRegisteredException;
import org.apache.openejb.persistence.JtaEntityManagerRegistry;
import org.apache.openejb.spi.SecurityService;
import org.apache.openejb.util.Duration;
import org.apache.openejb.util.Index;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;

import javax.ejb.EJBAccessException;
import javax.ejb.EJBContext;
import javax.ejb.EJBException;
import javax.ejb.EJBHome;
import javax.ejb.EJBLocalHome;
import javax.ejb.RemoveException;
import javax.ejb.SessionBean;
import javax.ejb.SessionContext;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.transaction.Transaction;
import java.lang.reflect.Method;
import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.dgc.VMID;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.openejb.core.ExceptionType.APPLICATION_ROLLBACK;
import static org.apache.openejb.core.ExceptionType.SYSTEM;
import static org.apache.openejb.core.transaction.EjbTransactionUtil.createTransactionPolicy;
import static org.apache.openejb.core.transaction.EjbTransactionUtil.handleApplicationException;
import static org.apache.openejb.core.transaction.EjbTransactionUtil.handleSystemException;

public class ManagedContainer implements RpcContainer {
    private static final Logger logger = Logger.getInstance(LogCategory.OPENEJB, "org.apache.openejb.util.resources");

    private final Object containerID;
    private final SecurityService securityService;

    // todo this should be part of the constructor
    protected final JtaEntityManagerRegistry entityManagerRegistry = SystemInstance.get().getComponent(JtaEntityManagerRegistry.class);

    /**
     * Index used for getDeployments() and getDeploymentInfo(deploymentId).
     */
    protected final Map<Object, BeanContext> deploymentsById = new HashMap<Object, BeanContext>();

    protected final Cache<Object, Instance> cache;
    private final ConcurrentHashMap<Object, Instance> checkedOutInstances = new ConcurrentHashMap<Object, Instance>();
    private final SessionContext sessionContext;

    public ManagedContainer(Object id, SecurityService securityService) throws SystemException {
        this.cache = new SimpleCache<Object, Instance>(null, new SimplePassivater(), 1000, 50, new Duration("1 hour"));
        this.containerID = id;
        this.securityService = securityService;
        cache.setListener(new StatefulCacheListener());
        sessionContext = new ManagedContext(securityService, new ManagedUserTransaction(new EjbUserTransaction(), entityManagerRegistry));
    }

    private Map<Method, MethodType> getLifecycleMethodsOfInterface(BeanContext beanContext) {
        Map<Method, MethodType> methods = new HashMap<Method, MethodType>();

        List<Method> removeMethods = beanContext.getRemoveMethods();
        for (Method removeMethod : removeMethods) {
            methods.put(removeMethod, MethodType.REMOVE);

            for (Class businessLocal : beanContext.getBusinessLocalInterfaces()) {
                try {
                    Method method = businessLocal.getMethod(removeMethod.getName());
                    methods.put(method, MethodType.REMOVE);
                } catch (NoSuchMethodException thatsFine) {
                }
            }

            for (Class businessRemote : beanContext.getBusinessRemoteInterfaces()) {
                try {
                    Method method = businessRemote.getMethod(removeMethod.getName());
                    methods.put(method, MethodType.REMOVE);
                } catch (NoSuchMethodException thatsFine) {
                }
            }
        }

        Class legacyRemote = beanContext.getRemoteInterface();
        if (legacyRemote != null) {
            try {
                Method method = legacyRemote.getMethod("remove");
                methods.put(method, MethodType.REMOVE);
            } catch (NoSuchMethodException thatsFine) {
            }
        }

        Class legacyLocal = beanContext.getLocalInterface();
        if (legacyLocal != null) {
            try {
                Method method = legacyLocal.getMethod("remove");
                methods.put(method, MethodType.REMOVE);
            } catch (NoSuchMethodException thatsFine) {
            }
        }

        Class businessLocalHomeInterface = beanContext.getBusinessLocalInterface();
        if (businessLocalHomeInterface != null) {
            for (Method method : BeanContext.BusinessLocalHome.class.getMethods()) {
                if (method.getName().startsWith("create")) {
                    methods.put(method, MethodType.CREATE);
                } else if (method.getName().equals("remove")) {
                    methods.put(method, MethodType.REMOVE);
                }
            }
        }

        Class businessLocalBeanHomeInterface = beanContext.getBusinessLocalBeanInterface();
        if (businessLocalBeanHomeInterface != null) {
            for (Method method : BeanContext.BusinessLocalBeanHome.class.getMethods()) {
                if (method.getName().startsWith("create")) {
                    methods.put(method, MethodType.CREATE);
                } else if (method.getName().equals("remove")) {
                    methods.put(method, MethodType.REMOVE);
                }
            }
        }

        Class businessRemoteHomeInterface = beanContext.getBusinessRemoteInterface();
        if (businessRemoteHomeInterface != null) {
            for (Method method : BeanContext.BusinessRemoteHome.class.getMethods()) {
                if (method.getName().startsWith("create")) {
                    methods.put(method, MethodType.CREATE);
                } else if (method.getName().equals("remove")) {
                    methods.put(method, MethodType.REMOVE);
                }
            }
        }

        Class homeInterface = beanContext.getHomeInterface();
        if (homeInterface != null) {
            for (Method method : homeInterface.getMethods()) {
                if (method.getName().startsWith("create")) {
                    methods.put(method, MethodType.CREATE);
                } else if (method.getName().equals("remove")) {
                    methods.put(method, MethodType.REMOVE);
                }
            }
        }

        Class localHomeInterface = beanContext.getLocalHomeInterface();
        if (localHomeInterface != null) {
            for (Method method : localHomeInterface.getMethods()) {
                if (method.getName().startsWith("create")) {
                    methods.put(method, MethodType.CREATE);
                } else if (method.getName().equals("remove")) {
                    methods.put(method, MethodType.REMOVE);
                }
            }
        }
        return methods;
    }

    public static enum MethodType {
        CREATE, REMOVE, BUSINESS
    }

    public ContainerType getContainerType() {
        return ContainerType.STATEFUL;
    }

    public Object getContainerID() {
        return containerID;
    }

    public synchronized BeanContext[] getBeanContexts() {
        return deploymentsById.values().toArray(new BeanContext[deploymentsById.size()]);
    }

    public synchronized BeanContext getBeanContext(Object deploymentID) {
        return deploymentsById.get(deploymentID);
    }

    public void start(BeanContext beanContext) throws OpenEJBException {
    }
    
    public void stop(BeanContext beanContext) throws OpenEJBException {
    }

    public synchronized void undeploy(final BeanContext bean) throws OpenEJBException {
        Data data = (Data) bean.getContainerData();

        MBeanServer server = LocalMBeanServer.get();
        for (ObjectName objectName : data.jmxNames) {
            try {
                server.unregisterMBean(objectName);
            } catch (Exception e) {
                logger.error("Unable to unregister MBean "+objectName);
            }
        }

        deploymentsById.remove(bean.getDeploymentID());
        bean.setContainer(null);
        bean.setContainerData(null);

        cache.removeAll(new CacheFilter<Instance>() {
            public boolean matches(Instance instance) {
                return bean == instance.beanContext;
            }
        });
    }

    public synchronized void deploy(BeanContext beanContext) throws OpenEJBException {
        Map<Method, MethodType> methods = getLifecycleMethodsOfInterface(beanContext);

        deploymentsById.put(beanContext.getDeploymentID(), beanContext);
        beanContext.setContainer(this);
        Data data = new Data(new Index<Method, MethodType>(methods));
        beanContext.setContainerData(data);

        // Create stats interceptor
        if (StatsInterceptor.isStatsActivated()) {
            StatsInterceptor stats = new StatsInterceptor(beanContext.getBeanClass());
            beanContext.addFirstSystemInterceptor(stats);

            MBeanServer server = LocalMBeanServer.get();

            ObjectNameBuilder jmxName = new ObjectNameBuilder("openejb.management");
            jmxName.set("J2EEServer", "openejb");
            jmxName.set("J2EEApplication", null);
            jmxName.set("EJBModule", beanContext.getModuleID());
            jmxName.set("StatelessSessionBean", beanContext.getEjbName());
            jmxName.set("j2eeType", "");
            jmxName.set("name", beanContext.getEjbName());

            // register the invocation stats interceptor
            try {
                ObjectName objectName = jmxName.set("j2eeType", "Invocations").build();
                server.registerMBean(new ManagedMBean(stats), objectName);
                data.jmxNames.add(objectName);
            } catch (Exception e) {
                logger.error("Unable to register MBean ", e);
            }
        }

        try {
            final Context context = beanContext.getJndiEnc();
            context.bind("comp/EJBContext", sessionContext);
        } catch (NamingException e) {
            throw new OpenEJBException("Failed to bind EJBContext", e);
        }

        beanContext.set(EJBContext.class, this.sessionContext);
    }

    /**
     * @deprecated use invoke signature without 'securityIdentity' argument.
     */
    public Object invoke(Object deployID, Method callMethod, Object[] args, Object primKey, Object securityIdentity) throws OpenEJBException {
        return invoke(deployID, null, callMethod.getDeclaringClass(), callMethod, args, primKey);
    }

    public Object invoke(Object deployID, Class callInterface, Method callMethod, Object[] args, Object primKey) throws OpenEJBException {
        return invoke(deployID, null, callInterface, callMethod, args, primKey);
    }

    public Object invoke(Object deployID, InterfaceType type, Class callInterface, Method callMethod, Object[] args, Object primKey) throws OpenEJBException {
        BeanContext beanContext = this.getBeanContext(deployID);

        if (beanContext == null) throw new OpenEJBException("Deployment does not exist in this container. Deployment(id='"+deployID+"'), Container(id='"+containerID+"')");

        // Use the backup way to determine call type if null was supplied.
        if (type == null) type = beanContext.getInterfaceType(callInterface);

        Data data = (Data) beanContext.getContainerData();
        MethodType methodType = data.getMethodIndex().get(callMethod);
        methodType = (methodType != null) ? methodType : MethodType.BUSINESS;

        switch (methodType) {
            case CREATE:
                return createEJBObject(beanContext, callMethod, args, type);
            case REMOVE:
                return removeEJBObject(beanContext, primKey, callInterface, callMethod, args, type);
            default:
                return businessMethod(beanContext, primKey, callInterface, callMethod, args, type);
        }


    }

    protected ProxyInfo createEJBObject(BeanContext beanContext, Method callMethod, Object[] args, InterfaceType interfaceType) throws OpenEJBException {
        // generate a new primary key
        Object primaryKey = newPrimaryKey();


        ThreadContext createContext = new ThreadContext(beanContext, primaryKey);
        ThreadContext oldCallContext = ThreadContext.enter(createContext);
        try {
            // Security check
            checkAuthorization(callMethod, interfaceType);

            // Create the extended entity managers for this instance
            Index<EntityManagerFactory, JtaEntityManagerRegistry.EntityManagerTracker> entityManagers = createEntityManagers(beanContext);

            // Register the newly created entity managers
            if (entityManagers != null) {
                try {
                    entityManagerRegistry.addEntityManagers((String) beanContext.getDeploymentID(), primaryKey, entityManagers);
                } catch (EntityManagerAlreadyRegisteredException e) {
                    throw new EJBException(e);
                }
            }

            createContext.setCurrentOperation(Operation.CREATE);
            createContext.setCurrentAllowedStates(null);

            // Start transaction
            TransactionPolicy txPolicy = createTransactionPolicy(createContext.getBeanContext().getTransactionType(callMethod, interfaceType), createContext);

            Instance instance = null;
            try {
                // Create new instance

                try {
                    final InstanceContext context = beanContext.newInstance();

                    // Wrap-up everthing into a object
                    instance = new Instance(beanContext, primaryKey, context.getBean(), context.getInterceptors(), context.getCreationalContext(), entityManagers);

                } catch (Throwable throwable) {
                    ThreadContext callContext = ThreadContext.getThreadContext();
                    handleSystemException(callContext.getTransactionPolicy(), throwable, callContext);
                    throw new IllegalStateException(throwable); // should never be reached
                }

                // add to cache
                cache.add(primaryKey, instance);

                // instance starts checked-out
                checkedOutInstances.put(primaryKey, instance);

                // Register for synchronization callbacks
                registerSessionSynchronization(instance, createContext);

                // Invoke create for legacy beans
                if (!callMethod.getDeclaringClass().equals(BeanContext.BusinessLocalHome.class) &&
                        !callMethod.getDeclaringClass().equals(BeanContext.BusinessRemoteHome.class) &&
                        !callMethod.getDeclaringClass().equals(BeanContext.BusinessLocalBeanHome.class)) {

                    // Setup for business invocation
                    Method createOrInit = beanContext.getMatchingBeanMethod(callMethod);
                    createContext.set(Method.class, createOrInit);

                    // Initialize interceptor stack
                    InterceptorStack interceptorStack = new InterceptorStack(instance.bean, createOrInit, Operation.CREATE, new ArrayList<InterceptorData>(), new HashMap<String, Object>());

                    // Invoke
                    if (args == null){
                        interceptorStack.invoke();
                    } else {
                        interceptorStack.invoke(args);
                    }
                }
            } catch (Throwable e) {
                handleException(createContext, txPolicy, e);
            } finally {
                afterInvoke(createContext, txPolicy, instance);
            }

            return new ProxyInfo(beanContext, primaryKey);
        } finally {
            ThreadContext.exit(oldCallContext);
        }
    }

    protected Object newPrimaryKey() {
        return new VMID();
    }

    protected Object removeEJBObject(BeanContext beanContext, Object primKey, Class callInterface, Method callMethod, Object[] args, InterfaceType interfaceType) throws OpenEJBException {
        if (primKey == null) throw new NullPointerException("primKey is null");

        ThreadContext callContext = new ThreadContext(beanContext, primKey);
        ThreadContext oldCallContext = ThreadContext.enter(callContext);
        try {
            // Security check
            checkAuthorization(callMethod, interfaceType);

            // If a bean managed transaction is active, the bean can not be removed
            if (interfaceType.isComponent()) {
                Instance instance = checkedOutInstances.get(primKey);

                /**
                 * According to EJB 3.0 "4.4.4 Restrictions for Transactions" any remove methods
                 * from home or component interfaces must not be allowed if the bean instance is
                 * in a transaction.  Unfortunately, the Java EE 5 TCK has tests that ignore the
                 * restrictions in 4.4.4 and expect beans in transactions can be removed via their
                 * home or component interface.   The test to see if the bean instance implements
                 * javax.ejb.SessionBean is a workaround for passing the TCK while the tests in
                 * question can be challenged or the spec can be changed/updated.
                 */
                if (instance != null && instance.bean instanceof javax.ejb.SessionBean) {
                    throw new ApplicationException(new RemoveException("A stateful EJB enrolled in a transaction can not be removed"));
                }
            }

            // Start transaction
            TransactionPolicy txPolicy = createTransactionPolicy(callContext.getBeanContext().getTransactionType(callMethod, interfaceType), callContext);

            Object returnValue = null;
            boolean retain = false;
            Instance instance = null;
            Method runMethod = null;
            try {
                // Obtain instance
                instance = obtainInstance(primKey, callContext);

                // Resume previous Bean transaction if there was one
                if (txPolicy instanceof BeanTransactionPolicy){
                    // Resume previous Bean transaction if there was one
                    SuspendedTransaction suspendedTransaction = instance.getBeanTransaction();
                    if (suspendedTransaction != null) {
                        instance.setBeanTransaction(null);
                        BeanTransactionPolicy beanTxEnv = (BeanTransactionPolicy) txPolicy;
                        beanTxEnv.resumeUserTransaction(suspendedTransaction);
                    }
                }

                // Register the entity managers
                registerEntityManagers(instance, callContext);

                // Register for synchronization callbacks
                registerSessionSynchronization(instance, callContext);

                // Setup for remove invocation
                callContext.setCurrentOperation(Operation.REMOVE);
                callContext.setCurrentAllowedStates(null);
                callContext.setInvokedInterface(callInterface);
                runMethod = beanContext.getMatchingBeanMethod(callMethod);
                callContext.set(Method.class, runMethod);

                // Do not pass arguments on home.remove(remote) calls
                Class<?> declaringClass = callMethod.getDeclaringClass();
                if (declaringClass.equals(EJBHome.class) || declaringClass.equals(EJBLocalHome.class)){
                    args = new Object[]{};
                }
                
                // Initialize interceptor stack
                List<InterceptorData> interceptors = beanContext.getMethodInterceptors(runMethod);
                InterceptorStack interceptorStack = new InterceptorStack(instance.bean, runMethod, Operation.REMOVE, interceptors, instance.interceptors);

                // Invoke
                if (args == null){
                    returnValue = interceptorStack.invoke();
                } else {
                    returnValue = interceptorStack.invoke(args);
                }
            } catch (InvalidateReferenceException e) {
                throw e;
            } catch (Throwable e) {
                if (interfaceType.isBusiness()) {
                    retain = beanContext.retainIfExeption(runMethod);
                    handleException(callContext, txPolicy, e);
                } else {
                    try {
                        handleException(callContext, txPolicy, e);
                    } catch (ApplicationException ae){
                        // Don't throw application exceptions for non-business interface removes
                    }
                }
            } finally {
                if (!retain) {
                    try {
                        callContext.setCurrentOperation(Operation.PRE_DESTROY);
                        List<InterceptorData> callbackInterceptors = beanContext.getCallbackInterceptors();
                        InterceptorStack interceptorStack = new InterceptorStack(instance.bean, null, Operation.PRE_DESTROY, callbackInterceptors, instance.interceptors);
                        interceptorStack.invoke();
                    } catch (Throwable callbackException) {
                        String logMessage = "An unexpected exception occured while invoking the preDestroy method on the removed Stateful SessionBean instance; " + callbackException.getClass().getName() + " " + callbackException.getMessage();

                        /* [1] Log the exception or error */
                        logger.error(logMessage);

                    } finally {
                        callContext.setCurrentOperation(Operation.REMOVE);
                    }

                    // todo destroy extended persistence contexts
                    discardInstance(callContext);
                }

                // Commit transaction
                afterInvoke(callContext, txPolicy, instance);
            }

            return returnValue;
        } finally {
            ThreadContext.exit(oldCallContext);
        }
    }

    protected Object businessMethod(BeanContext beanContext, Object primKey, Class callInterface, Method callMethod, Object[] args, InterfaceType interfaceType) throws OpenEJBException {
        ThreadContext callContext = new ThreadContext(beanContext, primKey);
        ThreadContext oldCallContext = ThreadContext.enter(callContext);
        try {
            // Security check
            checkAuthorization(callMethod, interfaceType);

            // Start transaction
            TransactionPolicy txPolicy = createTransactionPolicy(callContext.getBeanContext().getTransactionType(callMethod, interfaceType), callContext);

            Object returnValue = null;
            Instance instance = null;
            try {
                // Obtain instance
                instance = obtainInstance(primKey, callContext);

                // Resume previous Bean transaction if there was one
                if (txPolicy instanceof BeanTransactionPolicy){
                    SuspendedTransaction suspendedTransaction = instance.getBeanTransaction();
                    if (suspendedTransaction != null) {
                        instance.setBeanTransaction(null);
                        BeanTransactionPolicy beanTxEnv = (BeanTransactionPolicy) txPolicy;
                        beanTxEnv.resumeUserTransaction(suspendedTransaction);
                    }
                }

                // Register the entity managers
                registerEntityManagers(instance, callContext);
                // Register for synchronization callbacks
                registerSessionSynchronization(instance, callContext);

                // Setup for business invocation
                callContext.setCurrentOperation(Operation.BUSINESS);
                callContext.setCurrentAllowedStates(null);
                callContext.setInvokedInterface(callInterface);
                Method runMethod = beanContext.getMatchingBeanMethod(callMethod);
                callContext.set(Method.class, runMethod);

                // Initialize interceptor stack
                List<InterceptorData> interceptors = beanContext.getMethodInterceptors(runMethod);
                InterceptorStack interceptorStack = new InterceptorStack(instance.bean, runMethod, Operation.BUSINESS, interceptors, instance.interceptors);

                // Invoke
                returnValue = interceptorStack.invoke(args);
            } catch (Throwable e) {
                handleException(callContext, txPolicy, e);
            } finally {
                // Commit transaction
                afterInvoke(callContext, txPolicy, instance);
            }
            return returnValue;
        } finally {
            ThreadContext.exit(oldCallContext);
        }
    }

    private Instance obtainInstance(Object primaryKey, ThreadContext callContext) throws OpenEJBException {
        if (primaryKey == null) {
            throw new SystemException(new NullPointerException("Cannot obtain an instance of the stateful session bean with a null session id"));
        }

        Transaction currentTransaction = getTransaction(callContext);

        // Find the instance
        Instance instance = checkedOutInstances.get(primaryKey);
        if (instance == null) {
            try {
                instance = cache.checkOut(primaryKey);
            } catch (OpenEJBException e) {
                throw e;
            } catch (Exception e) {
                throw new SystemException("Unexpected load exception", e);
            }

            // Did we find the instance?
            if (instance == null) {
                throw new InvalidateReferenceException(new NoSuchObjectException("Not Found"));
            }

            // remember instance until it is returned to the cache
            checkedOutInstances.put(primaryKey, instance);
        }


        synchronized (instance) {
            // Is the instance alreayd in use?
            if (instance.isInUse()) {
                // the bean is already being invoked; the only reentrant/concurrent operations allowed are Session synchronization callbacks
                Operation currentOperation = callContext.getCurrentOperation();
                if (currentOperation != Operation.AFTER_COMPLETION && currentOperation != Operation.BEFORE_COMPLETION) {
                    throw new ApplicationException(new RemoteException("Concurrent calls not allowed."));
                }
            }

            if (instance.getTransaction() != null){
                if (!instance.getTransaction().equals(currentTransaction) && !instance.getLock().tryLock()) {
                    throw new ApplicationException(new RemoteException("Instance is in a transaction and cannot be invoked outside that transaction.  See EJB 3.0 Section 4.4.4"));
                }
            } else {
                instance.setTransaction(currentTransaction);
            }

            // Mark the instance in use so we can detect reentrant calls
            instance.setInUse(true);

            return instance;
        }
    }

    private Transaction getTransaction(ThreadContext callContext) {
        TransactionPolicy policy = callContext.getTransactionPolicy();

        Transaction currentTransaction = null;
        if (policy instanceof JtaTransactionPolicy) {
            JtaTransactionPolicy jtaPolicy = (JtaTransactionPolicy) policy;

            currentTransaction = jtaPolicy.getCurrentTransaction();
        }
        return currentTransaction;
    }

    private void releaseInstance(Instance instance) {
        // Don't pool if the bean has been undeployed
        if (instance.beanContext.isDestroyed()) return;

        // verify the instance is not associated with a bean-managed transaction
        if (instance.getBeanTransaction() != null) {
            new IllegalStateException("Instance has an active bean-managed transaction");
        }

        // no longer in use
        instance.setInUse(false);

        if (instance.getTransaction() == null) {
            // return to cache
            cache.checkIn(instance.primaryKey);

            // no longer checked out
            checkedOutInstances.remove(instance.primaryKey);
        }
    }

    private void discardInstance(ThreadContext threadContext) {
        Object primaryKey = threadContext.getPrimaryKey();
        if (primaryKey == null) {
            return;
        }

        Instance instance = checkedOutInstances.remove(primaryKey);
        cache.remove(primaryKey);
        if (instance.creationalContext != null) {
            instance.creationalContext.release();
        }
    }

    private void checkAuthorization(Method callMethod, InterfaceType interfaceType) throws ApplicationException {
        boolean authorized = securityService.isCallerAuthorized(callMethod, interfaceType);
        if (!authorized) {
            throw new ApplicationException(new EJBAccessException("Unauthorized Access by Principal Denied"));
        }
    }

    private void handleException(ThreadContext callContext, TransactionPolicy txPolicy, Throwable e) throws ApplicationException {
        if (e instanceof ApplicationException) {
            throw (ApplicationException) e;
        }

        ExceptionType type = callContext.getBeanContext().getExceptionType(e);
        if (type == SYSTEM) {
            discardInstance(callContext);
            handleSystemException(txPolicy, e, callContext);
        } else {
            handleApplicationException(txPolicy, e, type == APPLICATION_ROLLBACK);
        }
    }

    private void afterInvoke(ThreadContext callContext, TransactionPolicy txPolicy, Instance instance) throws OpenEJBException {
        try {
            unregisterEntityManagers(instance, callContext);
            if (instance != null && txPolicy instanceof BeanTransactionPolicy) {
                // suspend the currently running transaction if any
                SuspendedTransaction suspendedTransaction = null;
                try {
                    BeanTransactionPolicy beanTxEnv = (BeanTransactionPolicy) txPolicy;
                    suspendedTransaction = beanTxEnv.suspendUserTransaction();
                } catch (SystemException e) {
                    handleSystemException(txPolicy, e, callContext);
                } finally {
                    instance.setBeanTransaction(suspendedTransaction);
                }
            }
        } finally {
            if (instance != null) {
                instance.setInUse(false);
            }
            EjbTransactionUtil.afterInvoke(txPolicy, callContext);
        }
    }

    private Index<EntityManagerFactory, JtaEntityManagerRegistry.EntityManagerTracker> createEntityManagers(BeanContext beanContext) {
        // create the extended entity managers
        Index<EntityManagerFactory, Map> factories = beanContext.getExtendedEntityManagerFactories();
        Index<EntityManagerFactory, JtaEntityManagerRegistry.EntityManagerTracker> entityManagers = null;
        if (factories != null && factories.size() > 0) {
            entityManagers  = new Index<EntityManagerFactory, JtaEntityManagerRegistry.EntityManagerTracker>(new ArrayList<EntityManagerFactory>(factories.keySet()));
            for (Map.Entry<EntityManagerFactory, Map> entry : factories.entrySet()) {
                EntityManagerFactory entityManagerFactory = entry.getKey();
                Map properties = entry.getValue();


                JtaEntityManagerRegistry.EntityManagerTracker entityManagerTracker = entityManagerRegistry.getInheritedEntityManager(entityManagerFactory);
                EntityManager entityManager;
                if (entityManagerTracker == null) {
                    if (properties != null) {
                        entityManager = entityManagerFactory.createEntityManager(properties);
                    } else {
                        entityManager = entityManagerFactory.createEntityManager();
                    }
                    entityManagerTracker = new JtaEntityManagerRegistry.EntityManagerTracker(entityManager);
                } else {
                    entityManagerTracker.incCounter();
                }
                entityManagers .put(entityManagerFactory, entityManagerTracker);
            }
        }
        return entityManagers ;
    }

    private void registerEntityManagers(Instance instance, ThreadContext callContext) throws OpenEJBException {
        if (entityManagerRegistry == null) return;

        BeanContext beanContext = callContext.getBeanContext();

        // get the factories
        Index<EntityManagerFactory, Map> factories = beanContext.getExtendedEntityManagerFactories();
        if (factories == null) return;

        // get the managers for the factories
        Map<EntityManagerFactory, JtaEntityManagerRegistry.EntityManagerTracker> entityManagers = instance.getEntityManagers(factories);
        if (entityManagers == null) return;

        // register them
        try {
            entityManagerRegistry.addEntityManagers((String) beanContext.getDeploymentID(), instance.primaryKey, entityManagers);
        } catch (EntityManagerAlreadyRegisteredException e) {
            throw new EJBException(e);
        }
    }

    private void unregisterEntityManagers(Instance instance, ThreadContext callContext) {
        if (entityManagerRegistry == null) return;
        if (instance == null) return;

        BeanContext beanContext = callContext.getBeanContext();

        // register them
        entityManagerRegistry.removeEntityManagers((String) beanContext.getDeploymentID(), instance.primaryKey);
    }


    private void registerSessionSynchronization(Instance instance, ThreadContext callContext)  {
        TransactionPolicy txPolicy = callContext.getTransactionPolicy();
        if (txPolicy == null) {
            throw new IllegalStateException("ThreadContext does not contain a TransactionEnvironment");
        }

        SessionSynchronizationCoordinator coordinator = (SessionSynchronizationCoordinator) txPolicy.getResource(SessionSynchronizationCoordinator.class);
        if (coordinator == null) {
            coordinator = new SessionSynchronizationCoordinator(txPolicy);
            txPolicy.registerSynchronization(coordinator);
            txPolicy.putResource(SessionSynchronizationCoordinator.class, coordinator);
        }

        // SessionSynchronization are only enabled for beans after CREATE that are not bean-managed and implement the SessionSynchronization interface
        boolean synchronize = callContext.getCurrentOperation() != Operation.CREATE &&
                callContext.getBeanContext().isSessionSynchronized() &&
                txPolicy.isTransactionActive();

        coordinator.registerSessionSynchronization(instance, callContext.getBeanContext(), callContext.getPrimaryKey(), synchronize);
    }

    /**
     * SessionSynchronizationCoordinator handles afterBegin, beforeCompletion and afterCompletion callbacks.
     *
     * This class also is responsible for calling releaseInstance after the transaction completes. 
     */
    private class SessionSynchronizationCoordinator implements TransactionSynchronization {
        private final Map<Object, Synchronization> registry = new HashMap<Object, Synchronization>();
        private final TransactionPolicy txPolicy;

        private SessionSynchronizationCoordinator(TransactionPolicy txPolicy) {
            this.txPolicy = txPolicy;
        }

        public class Synchronization {
            private final Instance instance;

            private boolean callSessionSynchronization;

            public Synchronization(Instance instance) {
                this.instance = instance;
            }

            public synchronized boolean isCallSessionSynchronization() {
                return callSessionSynchronization;
            }

            public synchronized boolean setCallSessionSynchronization(boolean synchronize) {
                boolean oldValue = this.callSessionSynchronization;
                this.callSessionSynchronization = synchronize;
                return oldValue;
            }

        }

        private void registerSessionSynchronization(Instance instance, BeanContext beanContext, Object primaryKey, boolean synchronize) {

            Synchronization synchronization = registry.get(primaryKey);

            if (synchronization == null){
                synchronization = new Synchronization(instance);
                registry.put(primaryKey, synchronization);
            }

            boolean wasSynchronized = synchronization.setCallSessionSynchronization(synchronize);

            // check if afterBegin has already been invoked or if this is not a session synchronization bean
            if (wasSynchronized || !synchronize) {
                return;
            }

            // Invoke afterBegin
            ThreadContext callContext = new ThreadContext(instance.beanContext, instance.primaryKey, Operation.AFTER_BEGIN);
            callContext.setCurrentAllowedStates(null);
            ThreadContext oldCallContext = ThreadContext.enter(callContext);
            try {

                List<InterceptorData> interceptors = beanContext.getCallbackInterceptors();
                InterceptorStack interceptorStack = new InterceptorStack(instance.bean, null, Operation.AFTER_BEGIN, interceptors, instance.interceptors);
                interceptorStack.invoke();

            } catch (Exception e) {
                String message = "An unexpected system exception occured while invoking the afterBegin method on the SessionSynchronization object";

                // [1] Log the exception or error
                logger.error(message, e);

                // Caller handles transaction rollback and discardInstance

                // [4] throw the java.rmi.RemoteException to the client
                throw new OpenEJBRuntimeException(message, e);
            } finally {
                ThreadContext.exit(oldCallContext);
            }
        }

        public void beforeCompletion() {
            for (Synchronization synchronization : registry.values()) {

                Instance instance = synchronization.instance;

                // don't call beforeCompletion when transaction is marked rollback only
                if (txPolicy.isRollbackOnly()) return;

                // only call beforeCompletion on beans with session synchronization
                if (!synchronization.isCallSessionSynchronization()) continue;

                // Invoke beforeCompletion
                ThreadContext callContext = new ThreadContext(instance.beanContext, instance.primaryKey, Operation.BEFORE_COMPLETION);
                callContext.setCurrentAllowedStates(null);
                ThreadContext oldCallContext = ThreadContext.enter(callContext);
                try {
                    instance.setInUse(true);

                    BeanContext beanContext = instance.beanContext;
                    List<InterceptorData> interceptors = beanContext.getCallbackInterceptors();
                    InterceptorStack interceptorStack = new InterceptorStack(instance.bean, null, Operation.BEFORE_COMPLETION, interceptors, instance.interceptors);
                    interceptorStack.invoke();

                    instance.setInUse(false);
                } catch (InvalidateReferenceException e) {
                    // exception has alredy been handled
                } catch (Exception e) {
                    String message = "An unexpected system exception occured while invoking the beforeCompletion method on the SessionSynchronization object";

                    // [1] Log the exception or error
                    logger.error(message, e);

                    // [2] Mark the transaction for rollback.
                    txPolicy.setRollbackOnly(e);

                    // [3] Discard the instance
                    discardInstance(callContext);

                    // [4] throw the java.rmi.RemoteException to the client
                    throw new OpenEJBRuntimeException(message, e);
                } finally {
                    ThreadContext.exit(oldCallContext);
                }
            }
        }

        public void afterCompletion(Status status) {
            Throwable firstException = null;
            for (Synchronization synchronization : registry.values()) {

                Instance instance = synchronization.instance;

                ThreadContext callContext = new ThreadContext(instance.beanContext, instance.primaryKey, Operation.AFTER_COMPLETION);
                callContext.setCurrentAllowedStates(null);
                ThreadContext oldCallContext = ThreadContext.enter(callContext);
                try {
                    instance.setInUse(true);
                    if (synchronization.isCallSessionSynchronization()) {

                        BeanContext beanContext = instance.beanContext;
                        List<InterceptorData> interceptors = beanContext.getCallbackInterceptors();
                        InterceptorStack interceptorStack = new InterceptorStack(instance.bean, null, Operation.AFTER_COMPLETION, interceptors, instance.interceptors);
                        interceptorStack.invoke(status == Status.COMMITTED);
                    }
                    instance.setTransaction(null);
                    releaseInstance(instance);
                } catch (InvalidateReferenceException inv) {
                    // exception has alredy been handled
                } catch (Throwable e) {
                    String message = "An unexpected system exception occured while invoking the afterCompletion method on the SessionSynchronization object";

                    // [1] Log the exception or error
                    logger.error(message, e);

                    // Transaction is complete so can not be rolled back

                    // [3] Discard the instance
                    discardInstance(callContext);

                    // [4] throw throw first exception to the client
                    if (firstException == null) firstException = e;
                } finally {
                    ThreadContext.exit(oldCallContext);
                }
            }

            if (firstException != null) {
                throw new OpenEJBRuntimeException("An unexpected system exception occured while invoking the afterCompletion method on the SessionSynchronization object", firstException);
            }
        }
    }

    public class StatefulCacheListener implements CacheListener<Instance> {
        public void afterLoad(Instance instance) throws SystemException, ApplicationException {
            BeanContext beanContext = instance.beanContext;

            ThreadContext threadContext = new ThreadContext(instance.beanContext, instance.primaryKey, Operation.ACTIVATE);
            ThreadContext oldContext = ThreadContext.enter(threadContext);
            try {
                Method remove = instance.bean instanceof SessionBean ? SessionBean.class.getMethod("ejbActivate") : null;

                List<InterceptorData> callbackInterceptors = beanContext.getCallbackInterceptors();
                InterceptorStack interceptorStack = new InterceptorStack(instance.bean, remove, Operation.ACTIVATE, callbackInterceptors, instance.interceptors);

                interceptorStack.invoke();
            } catch (Throwable callbackException) {
                discardInstance(threadContext);
                handleSystemException(threadContext.getTransactionPolicy(), callbackException, threadContext);
            } finally {
                ThreadContext.exit(oldContext);
            }
        }

        public void beforeStore(Instance instance) {
            BeanContext beanContext = instance.beanContext;

            ThreadContext threadContext = new ThreadContext(beanContext, instance.primaryKey, Operation.PASSIVATE);
            ThreadContext oldContext = ThreadContext.enter(threadContext);
            try {
                Method passivate = instance.bean instanceof SessionBean ? SessionBean.class.getMethod("ejbPassivate") : null;

                List<InterceptorData> callbackInterceptors = beanContext.getCallbackInterceptors();
                InterceptorStack interceptorStack = new InterceptorStack(instance.bean, passivate, Operation.PASSIVATE, callbackInterceptors, instance.interceptors);

                interceptorStack.invoke();

            } catch (Throwable e) {
                logger.error("An unexpected exception occured while invoking the ejbPassivate method on the Stateful SessionBean instance", e);
            } finally {
                ThreadContext.exit(oldContext);
            }
        }

        public void timedOut(Instance instance) {
            BeanContext beanContext = instance.beanContext;

            ThreadContext threadContext = new ThreadContext(beanContext, instance.primaryKey, Operation.PRE_DESTROY);
            threadContext.setCurrentAllowedStates(null);
            ThreadContext oldContext = ThreadContext.enter(threadContext);
            try {
                Method remove = instance.bean instanceof SessionBean ? SessionBean.class.getMethod("ejbRemove") : null;

                List<InterceptorData> callbackInterceptors = beanContext.getCallbackInterceptors();
                InterceptorStack interceptorStack = new InterceptorStack(instance.bean, remove, Operation.PRE_DESTROY, callbackInterceptors, instance.interceptors);

                interceptorStack.invoke();
            } catch (Throwable e) {
                logger.error("An unexpected exception occured while invoking the ejbRemove method on the timed-out Stateful SessionBean instance", e);
            } finally {
                logger.info("Removing the timed-out stateful session bean instance " + instance.primaryKey);
                ThreadContext.exit(oldContext);
            }
        }
    }    

    private static class Data {
        private final Index<Method, MethodType> methodIndex;
        private final List<ObjectName> jmxNames = new ArrayList<ObjectName>();
        
        private Data(Index<Method, MethodType> methodIndex) {
            this.methodIndex = methodIndex;
        }

        public Index<Method, MethodType> getMethodIndex() {
            return methodIndex;
        }

    }
}
