process SLEEP_ECHO {

    input:
    tuple val(meta), val(second_value)

    script:
    def random_sleep = 15 + (int)(Math.random() * 30)  // Random sleep between 15-45 seconds
    """
    sleep ${random_sleep}
    echo 'OK.. worked for ${meta.id} after ${random_sleep} seconds'
    """
}

process SLEEP_FAIL {

    errorStrategy "ignore"

    input:
    tuple val(meta), val(second_value)

    script:
    def random_sleep = 15 + (int)(Math.random() * 20)  // Random sleep between 15-35 seconds
    """
    sleep ${random_sleep}
    echo 'Failing ${meta.id} after ${random_sleep} seconds'
    exit 1
    """
}


workflow {
    // Create a channel with proper tuple structure - extended to 20 samples
    def input_ch = Channel.of(
        [[id: 'sample1', name: 'test1'], 'value1'],
        [[id: 'sample2', name: 'test2'], 'value2'],
        [[id: 'sample3', name: 'test3'], 'value3'],
        [[id: 'sample4', name: 'test4'], 'value4'],
        [[id: 'sample5', name: 'test5'], 'value5'],
        [[id: 'sample6', name: 'test6'], 'value6'],
        [[id: 'sample7', name: 'test7'], 'value7'],
        [[id: 'sample8', name: 'test8'], 'value8'],
        [[id: 'sample9', name: 'test9'], 'value9'],
        [[id: 'sample10', name: 'test10'], 'value10'],
        [[id: 'sample11', name: 'test11'], 'value11'],
        [[id: 'sample12', name: 'test12'], 'value12'],
        [[id: 'sample13', name: 'test13'], 'value13'],
        [[id: 'sample14', name: 'test14'], 'value14'],
        [[id: 'sample15', name: 'test15'], 'value15'],
        [[id: 'sample16', name: 'test16'], 'value16'],
        [[id: 'sample17', name: 'test17'], 'value17'],
        [[id: 'sample18', name: 'test18'], 'value18'],
        [[id: 'sample19', name: 'test19'], 'value19'],
        [[id: 'sample20', name: 'test20'], 'value20']
    )

    SLEEP_ECHO(input_ch)

    SLEEP_FAIL(input_ch)
}