// Simple test pipeline with actual compute jobs using quay.io containers

// Data processing with CentOS container from quay.io
process DATA_PROCESSING {
    container 'quay.io/centos/centos:7'
    
    input:
    tuple val(meta), val(data_value)
    
    output:
    path "output_${meta.id}.txt"
    
    script:
    """
    echo "Processing sample ${meta.id} with data: ${data_value}"
    
    # Create some data and do simple processing
    for i in {1..50}; do
        echo "Line \$i for sample ${meta.id}" >> temp_data.txt
    done
    
    # Count lines and characters
    lines=\$(wc -l < temp_data.txt)
    chars=\$(wc -c < temp_data.txt)
    words=\$(wc -w < temp_data.txt)
    
    # Write results
    echo "Sample: ${meta.id}" > output_${meta.id}.txt
    echo "Lines processed: \$lines" >> output_${meta.id}.txt
    echo "Characters: \$chars" >> output_${meta.id}.txt
    echo "Words: \$words" >> output_${meta.id}.txt
    
    # Random sleep to simulate variable processing
    sleep \$((RANDOM % 2 + 1))
    """
}

// Text processing with CentOS container from quay.io
process TEXT_ANALYSIS {
    container 'quay.io/centos/centos:7'
    
    input:
    tuple val(meta), val(data)
    
    output:
    path "text_${meta.id}.txt"
    
    script:
    """
    # Generate and analyze text
    text="Sample ${meta.id} processing text data with some words and sentences for analysis"
    echo "\$text" > text_${meta.id}.txt
    
    # Simple text metrics
    words=\$(echo "\$text" | wc -w)
    chars=\$(echo "\$text" | wc -c)
    
    echo "Word count: \$words" >> text_${meta.id}.txt
    echo "Character count: \$chars" >> text_${meta.id}.txt
    
    sleep \$((RANDOM % 2))
    """
}

// Simple math calculations with CentOS container from quay.io
process SIMPLE_MATH {
    container 'quay.io/centos/centos:7'
    
    input:
    tuple val(meta), val(data)
    
    output:
    path "math_${meta.id}.txt"
    
    script:
    """
    # Simple math using basic shell commands
    echo "Math calculations for sample ${meta.id}" > math_${meta.id}.txt
    
    # Generate some numbers and do simple calculations
    for i in {1..10}; do
        num=\$((RANDOM % 100 + 1))
        echo "\$num" >> numbers.txt
    done
    
    # Calculate sum and average
    sum=\$(awk '{s+=\$1} END {print s}' numbers.txt)
    count=\$(wc -l < numbers.txt)
    avg=\$(echo "scale=2; \$sum / \$count" | bc)
    
    echo "Sum: \$sum" >> math_${meta.id}.txt
    echo "Count: \$count" >> math_${meta.id}.txt
    echo "Average: \$avg" >> math_${meta.id}.txt
    
    sleep \$((RANDOM % 2 + 1))
    """
}

workflow {
    // Create test data - 5 samples
    def samples = Channel.of(
        [[id: 'sample1', name: 'test1'], 'input_data_1'],
        [[id: 'sample2', name: 'test2'], 'input_data_2'],
        [[id: 'sample3', name: 'test3'], 'input_data_3'],
        [[id: 'sample4', name: 'test4'], 'input_data_4'],
        [[id: 'sample5', name: 'test5'], 'input_data_5']
    )
    
    // Run all processes
    DATA_PROCESSING(samples)
    TEXT_ANALYSIS(samples)
    SIMPLE_MATH(samples)
}