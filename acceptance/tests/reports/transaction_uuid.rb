require 'json'

test_name "validate matching transaction UUIDs in agent report and catalog" do
  step "setup a test manifest for the master and perform agent runs" do
    manifest = <<-MANIFEST
      node default {
        @@notify { "exported_resource": }
        notify { "non_exported_resource": }
     }
    MANIFEST

    run_agents_with_new_site_pp(master, manifest)
  end

  # Wait until all the commands have been processed
  sleep_until_queue_empty database

  agents.each do |agent|
    # Query for all of the reports for this node, but we only care about the most recent one
    result = on database, %Q|curl -G http://localhost:8080/v3/reports --data 'query=["=",%20"certname",%20"#{agent.node_name}"]'|
    report = JSON.parse(result.stdout)[0]

    # Query for the most recent catalog for this node
    result = on database, %Q|curl -G http://localhost:8080/v3/catalogs/#{agent.node_name}|
    catalog = JSON.parse(result.stdout)

    report_uuid = report['transaction-uuid']
    catalog_uuid = catalog['data']['transaction-uuid']
    report_format = report['report-format']

    if report_format < 4
      assert_equal(nil, report_uuid, 'Transaction UUID should be nil in reports before format 4')
      assert_equal(nil, catalog_uuid, 'Transaction UUID should be nil in catalogs before format 4')
    else
      assert_not_nil(report_uuid, 'Transaction UUID should not be nil')
      assert_equal(report_uuid, catalog_uuid, 'Most recent report & catalog should have the same transaction UUID')
    end
  end
end
