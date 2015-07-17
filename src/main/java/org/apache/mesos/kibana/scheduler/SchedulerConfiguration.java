package org.apache.mesos.kibana.scheduler;

import org.apache.mesos.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Contains the configuration for a KibanaScheduler.
 * Used to manage task settings and required/running tasks.
 */
public class SchedulerConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(SchedulerConfiguration.class);
    private static final String dockerImageName = "kibana"; // the name of Kibana Docker image to use when starting a task
    private static final double requiredCpu = 0.1D;                 // the amount of CPUs a task needs
    private static final double requiredMem = 128D;                 // the amount of memory a task needs
    private static final double requiredPortCount = 1D;                 // the amount of ports a task needs

    protected Map<String, Integer> requiredTasks = new HashMap<>();             // a map containing the required tasks: <elasticSearchUrl, numberOfInstances>
    protected Map<String, List<Protos.TaskID>> runningTasks = new HashMap<>();  // a map containing the currently running tasks: <elasticSearchUrl, listOfTaskIds>
    private Map<Protos.TaskID, Long> usedPortNumbers = new HashMap<>();               // a list containing the currently used ports, part of the Docker host ports workaround
    private String mesosMasterAddress;   // the address of the Mesos master

    /**
     * Returns the name of the Kibana Docker image
     *
     * @return the name of the Kibana Docker image
     */
    public static String getDockerImageName() {
        return dockerImageName;
    }

    /**
     * Returns the amount of requiredCpu a task needs
     *
     * @return the amount of requiredCpu a task needs
     */
    public static double getRequiredCpu() {
        return requiredCpu;
    }

    /**
     * Returns the amount of memory a task needs
     *
     * @return the amount of memory a task needs
     */
    public static double getRequiredMem() {
        return requiredMem;
    }

    /**
     * Returns the amount of ports a task needs
     *
     * @return the amount of ports a task needs
     */
    public static double getRequiredPortCount() {
        return requiredPortCount;
    }

    /**
     * Picks a port number from the given offer's port resources
     *
     * @param taskId
     * @param offer  the offer from which's resources to pick a port
     * @return a port number
     */
    public long pickAndRegisterPortNumber(Protos.TaskID taskId, Protos.Offer offer) {
        for (Protos.Resource resource : offer.getResourcesList()) {
            if (!resource.getName().equals("ports")) continue;

            List<Protos.Value.Range> offeredRanges = resource.getRanges().getRangeList();
            for (Protos.Value.Range portRange : offeredRanges) {
                long begin = portRange.getBegin();
                long end = portRange.getEnd();
                for (long port = begin; port < end; port++) {
                    if (!usedPortNumbers.values().contains(port)) {
                        usedPortNumbers.put(taskId, port);
                        return port;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Returns the address of the Mesos master
     *
     * @return the address of the Mesos master
     */
    public String getMesosMasterAddress() {
        return mesosMasterAddress;
    }

    /**
     * Sets the address of the Mesos master
     *
     * @param mesosMasterAddress the address of the mesos master
     */
    public void setMesosMasterAddress(String mesosMasterAddress) {
        logger.info("Setting Mesos master address to {}", mesosMasterAddress);
        this.mesosMasterAddress = mesosMasterAddress;
    }

    /**
     * Increases the required number of instances for the given elasticSearchUrl by the given amount.
     * If the resulting amount of required instances is equal to or lower than 0, the elasticSearchUrl entry is removed from the requiredTasks.
     *
     * @param elasticSearchUrl the elasticSearchUrl to change the required amount of instances for
     * @param amount           the amount by which to change the required amount of instances
     */
    public void registerRequirement(String elasticSearchUrl, int amount) {
        if (requiredTasks.containsKey(elasticSearchUrl)) {
            int newAmount = amount + requiredTasks.get(elasticSearchUrl).intValue();
            if (newAmount <= 0) {
                requiredTasks.remove(elasticSearchUrl);
                logger.info("RequiredInstances: No more instances are required for ElasticSearch {}", elasticSearchUrl);
            } else {
                requiredTasks.put(elasticSearchUrl, newAmount);
                logger.info("RequiredInstances: Now requiring {} instances for ElasticSearch {}", newAmount, elasticSearchUrl);
            }
        } else if (amount > 0) {
            requiredTasks.put(elasticSearchUrl, amount);
            logger.info("RequiredInstances: Now requiring {} instances for ElasticSearch {}", amount, elasticSearchUrl);
        }
    }

    /**
     * Returns a Map with all known elasticSearchUrls and the delta between the required and running number of instances.
     *
     * @return a Map with all known elasticSearchUrls and the delta between the required and running number of instances
     */
    public Map<String, Integer> getRequirementDeltaMap() {
        Set<String> elasticSearchUrls = new HashSet<>();
        elasticSearchUrls.addAll(requiredTasks.keySet());
        elasticSearchUrls.addAll(runningTasks.keySet());

        Map<String, Integer> requirementDeltaMap = new HashMap<>();
        for (String elasticSearchUrl : elasticSearchUrls) {
            requirementDeltaMap.put(elasticSearchUrl, getRequirementDelta(elasticSearchUrl));
        }
        return requirementDeltaMap;
    }

    /**
     * Calculates the delta between the required amount and the running amount of instances for the given elasticSearchUrl
     *
     * @param elasticSearchUrl the elasticSearchUrl to calculate the delta for
     * @return the delta between the required amount and the running amount of instances for the given elasticSearchUrl
     */
    private int getRequirementDelta(String elasticSearchUrl) {
        if (requiredTasks.containsKey(elasticSearchUrl)) {
            int requiredAmount = requiredTasks.get(elasticSearchUrl);
            if (runningTasks.containsKey(elasticSearchUrl)) {
                int actualAmount = runningTasks.get(elasticSearchUrl).size();
                return requiredAmount - actualAmount;
            }
            return requiredAmount;
        }

        if (runningTasks.containsKey(elasticSearchUrl)) {
            int actualAmount = runningTasks.get(elasticSearchUrl).size();
            return -actualAmount;
        }
        return 0;
    }

    /**
     * Handles any passed in arguments
     *
     * @param args the master hostname/port followed by elasticSearchUrls
     */
    public void parseLaunchArguments(String[] args) {
        setMesosMasterAddress(args[0]);
        for (int i = 1; i < args.length; i++) {
            String elasticSearchUrl = args[i];
            registerRequirement(elasticSearchUrl, 1);
        }
    }

    /**
     * Adds the given task to the currently running tasks, under the given elasticSearchUrl
     *
     * @param elasticSearchUrl the elasticSearchUrl under which to add the given task
     * @param taskId           the task to add
     */
    public void registerTask(String elasticSearchUrl, Protos.TaskID taskId) {
        if (runningTasks.containsKey(elasticSearchUrl)) {
            runningTasks.get(elasticSearchUrl).add(taskId);
        } else {
            ArrayList<Protos.TaskID> instances = new ArrayList<>();
            instances.add(taskId);
            runningTasks.put(elasticSearchUrl, instances);
        }
        logger.info("Now running task {} for ElasticSearch{}", taskId.getValue(), elasticSearchUrl);
    }

    /**
     * Unregisters the given task and its ports
     *
     * @param taskId the task to unregister
     */
    public void unregisterTask(Protos.TaskID taskId) {
        for (List<Protos.TaskID> tasks : runningTasks.values()) {
            if (tasks.contains(taskId)) {
                tasks.remove(taskId);
                usedPortNumbers.remove(taskId);
                logger.info("Removed task {} for ElasticSearch{}", taskId.getValue());
                return;
            }
        }
    }
}