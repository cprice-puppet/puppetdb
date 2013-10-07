if (test_config[:install_type] == :package)
  os = test_config[:os_families][database.name]

  step "Add development repository on PuppetDB server" do
    case os
    when :debian
      result = on database, "lsb_release -sc"
      deb_flavor = result.stdout
      apt_list_url = "#{test_config[:package_repo_url]}/deb/pl-puppetdb-#{test_config[:git_ref]}-#{deb_flavor}.list"
      apt_list_file_path = "/etc/apt/sources.list.d/puppetdb-prerelease.list"
      on database, "wget #{apt_list_url} > #{apt_list_file_path}"
      result = on database, "cat #{apt_list_file_path}"
      Log.notify("APT LIST FILE CONTENTS:\n#{result.stdout}\n")
      on database, "apt-get update"
    when :redhat
      # TODO: this code assumes that we are always running a 64-bit CentOS.  Will
      #  break with Fedora or RHEL.
      result = on database, "facter operatingsystemmajrelease"
      el_version = result.stdout
      yum_repo_url = "#{test_config[:package_repo_url]}/rpm/pl-puppetdb-#{test_config[:git_ref]}-el-#{el_version}-x86_64.repo"
      yum_repo_file_path = "/etc/yum.repos.d/puppetlabs-prerelease.repo"
      on database, "wget #{yum_repo_url} > #{yum_repo_file_path}"

      result = on database, "cat #{yum_repo_file_path}"
      Log.notify("Yum REPO DEFINITION:\n\n#{result.sdtout}\n\n")
      create_remote_file database, '/etc/yum.repos.d/puppetlabs-prerelease.repo', yum_repo
    else
      raise ArgumentError, "Unsupported OS '#{os}'"
    end
  end
end
